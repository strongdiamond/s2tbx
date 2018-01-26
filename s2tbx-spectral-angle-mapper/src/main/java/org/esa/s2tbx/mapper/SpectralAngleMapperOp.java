package org.esa.s2tbx.mapper;

import org.esa.s2tbx.mapper.common.SpectralAngleMapperConstants;
import org.esa.s2tbx.mapper.pixels.computing.SpectrumClassReferencePixelsContainer;
import org.esa.s2tbx.mapper.pixels.mean.Spectrum;
import org.esa.s2tbx.mapper.pixels.computing.SpectrumClassPixelsComputing;
import org.esa.s2tbx.mapper.pixels.computing.SpectrumClassReferencePixels;
import org.esa.s2tbx.mapper.pixels.mean.SpectrumComputing;
import org.esa.s2tbx.mapper.common.SpectrumInput;
import org.esa.s2tbx.mapper.pixels.mean.SpectrumContainer;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.util.ProductUtils;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * spectral angle mapper operator
 *
 * @author Razvan Dumitrascu
 */
@OperatorMetadata(
        alias = "SpectralAngleMapperOp",
        version="1.0",
        category = "Optical/Thematic Land Processing",
        description = "Classifies a product using the spectral angle mapper algorithm",
        authors = "Dumitrascu Razvan",
        copyright = "Copyright (C) 2017 by CS ROMANIA")

public class SpectralAngleMapperOp extends Operator {

    private static final String RESAMPLE_NONE = "None";
    private static final String RESAMPLE_LOWEST = "Lowest resolution";
    private static final String RESAMPLE_HIGHEST = "Highest resolution";

    @SourceProduct(alias = "source", description = "The source product.")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;


    @Parameter(alias = "referenceBands",
            label = "Reference Bands",
            description = "The reference bands to be used for the Spectral Angle Mapper Processor ")
    private String[] referenceBands;

    @Parameter(description = "Thresholds", defaultValue = "0.0")
    private String thresholds;

    @Parameter(description = "The list of spectra.", alias = "spectra")
    private SpectrumInput[] spectra;

    @Parameter(label = "Resample Type",
            description = "If selected bands differ in size, the resample method used before computing the index",
            defaultValue = RESAMPLE_NONE, valueSet = { RESAMPLE_NONE, RESAMPLE_LOWEST, RESAMPLE_HIGHEST })
    private String resampleType;

    @Parameter(alias = "upsampling",
            label = "Upsampling Method",
            description = "The method used for interpolation (upsampling to a finer resolution).",
            valueSet = {"Nearest", "Bilinear", "Bicubic"},
            defaultValue = "Nearest")
    private String upsamplingMethod;

    @Parameter(alias = "downsampling",
            label = "Downsampling Method",
            description = "The method used for aggregation (downsampling to a coarser resolution).",
            valueSet = {"First", "Min", "Max", "Mean", "Median"},
            defaultValue = "First")
    private String downsamplingMethod;

    private List<Double> threshold;
    private Map<String, Integer>  classColor;
    private SpectrumClassReferencePixelsContainer specPixelsContainer;
    private SpectrumContainer spectrumContainer;
    private int threadCount;

    @Override
    public void initialize() throws OperatorException {
        if (this.sourceProduct == null) {
            throw new OperatorException("Source product not set");
        }
        if(spectra.length == 0) {
            throw new OperatorException("No spectrum classes have been set");
        }

        int sceneWidth = 0, sceneHeight = 0;
        boolean resampleNeeded = !RESAMPLE_NONE.equals(this.resampleType);
        if (resampleNeeded) {
            for (String bandName : this.referenceBands) {
                Band band = this.sourceProduct.getBand(bandName);
                int bandRasterWidth = band.getRasterWidth();
                if (RESAMPLE_HIGHEST.equals(this.resampleType)) {
                    if (sceneWidth < bandRasterWidth) {
                        sceneWidth = bandRasterWidth;
                        sceneHeight = band.getRasterHeight();
                    }
                } else {
                    if (sceneWidth == 0 || sceneWidth >= bandRasterWidth) {
                        sceneWidth = bandRasterWidth;
                        sceneHeight = band.getRasterHeight();
                    }
                }
            }
            this.sourceProduct = resample(this.sourceProduct, sceneWidth, sceneHeight);

        } else {
            sceneWidth = sourceProduct.getSceneRasterWidth();
            sceneHeight = sourceProduct.getSceneRasterHeight();
            int firstSourceBandWidth = sourceProduct.getBand(this.referenceBands[0]).getRasterWidth();
            int firstSourceBandHeight = sourceProduct.getBand(this.referenceBands[0]).getRasterHeight();
            if(firstSourceBandWidth != sceneWidth || firstSourceBandHeight != sceneHeight ) {
                sceneWidth = firstSourceBandWidth;
                sceneHeight = firstSourceBandHeight;
            }
        }
        specPixelsContainer = new SpectrumClassReferencePixelsContainer();
        spectrumContainer = new SpectrumContainer();
        validateSpectra();
        this.targetProduct = new Product(SpectralAngleMapperConstants.TARGET_PRODUCT_NAME, this.sourceProduct.getProductType() + "_SAM", sceneWidth, sceneHeight);
        ProductUtils.copyTimeInformation(this.sourceProduct, this.targetProduct);

        Band samOutputBand = new Band(SpectralAngleMapperConstants.SAM_BAND_NAME, ProductData.TYPE_INT32, sceneWidth, sceneHeight);
        samOutputBand.setNoDataValueUsed(true);
        samOutputBand.setNoDataValue(SpectralAngleMapperConstants.NO_DATA_VALUE);
        this.targetProduct.addBand(samOutputBand);
        boolean sceneSizeRetained = this.sourceProduct.getSceneRasterSize().equals(this.targetProduct.getSceneRasterSize());
        if (sceneSizeRetained) {
            ProductUtils.copyTiePointGrids(this.sourceProduct, this.targetProduct);
            ProductUtils.copyGeoCoding(this.sourceProduct, this.targetProduct);
        }

        this.threadCount = Runtime.getRuntime().availableProcessors();
    }

    @Override
    public void doExecute(ProgressMonitor pm) throws OperatorException {

        this.threshold = new ArrayList<>();
        this.classColor = new HashMap<>();
        ExecutorService threadPool;
        int classColorLevel = 200;
        for(SpectrumInput spectrumInput : this.spectra) {
            this.classColor.put(spectrumInput.getName(), classColorLevel);
            classColorLevel += 200;
        }
        threadPool = Executors.newFixedThreadPool(threadCount);
        for (SpectrumInput aSpectra : spectra) {
            Runnable worker = new SpectrumClassPixelsComputing(aSpectra, specPixelsContainer);
            threadPool.execute(worker);
        }
        threadPool.shutdown();
        while (!threadPool.isTerminated()) {
            try {
                threadPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        threadPool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < spectra.length; i++) {
            Runnable worker = new SpectrumComputing(specPixelsContainer.getElements().get(i), this.sourceProduct, this.referenceBands, this.spectrumContainer);
            threadPool.execute(worker);
        }
        threadPool.shutdown();
        while (!threadPool.isTerminated()) {
            try {
                threadPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        parseThresholds();
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {
        pm.beginTask("Computing SpectralAngleMapperOp", rectangle.height);
        System.out.println("region x= " + rectangle.getX() +": y= " + rectangle.getY());
        try {
            List<Tile> sourceTileList = new ArrayList<>();
            for (int index = 0; index < this.sourceProduct.getNumBands(); index++) {
                if (Arrays.asList(this.referenceBands).contains(this.sourceProduct.getBandAt(index).getName())) {
                    sourceTileList.add(getSourceTile(getSourceProduct().getBandAt(index), rectangle));
                }
            }
            Tile samTile = targetTiles.get(this.targetProduct.getBand(SpectralAngleMapperConstants.SAM_BAND_NAME));
            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                    boolean isSet = false;
                    for(SpectrumClassReferencePixels spec: specPixelsContainer.getElements()){
                        for(int index = 0; index < spec.getXPixelPositions().size(); index++) {
                            if(x >= spec.getMinXPosition() && x <= spec.getMaxXPosition() &&
                                    y >= spec.getMinYPosition() && y <= spec.getMaxYPosition()) {
                                int xSpecPosition = spec.getXPixelPositions().get(index);
                                int ySpecPosition = spec.getYPixelPositions().get(index);
                                if (xSpecPosition == x && ySpecPosition == y) {
                                    samTile.setSample(x, y, this.classColor.get(spec.getClassName()));
                                    isSet = true;
                                }
                            }
                        }
                    }
                    if(!isSet) {
                        boolean setPixelColor = false;
                        double threshold = 1.0;
                        for (Spectrum spec : spectrumContainer.getElements()) {
                            float valueSum = 0;
                            float pixelValueSquareSum = 0;
                            float spectrumPixelValueSquareSum = 0;
                            for (int tileIndex = 0; tileIndex < sourceTileList.size(); tileIndex++) {
                                float testedPixelValue = sourceTileList.get(tileIndex).getSampleFloat(x, y);
                                valueSum += testedPixelValue * spec.getMeanValue()[tileIndex];
                                pixelValueSquareSum += Math.pow(testedPixelValue, 2);
                                spectrumPixelValueSquareSum += Math.pow(spec.getMeanValue()[tileIndex], 2);
                            }
                            double samAngle = Math.acos(valueSum / (Math.sqrt(pixelValueSquareSum) * (Math.sqrt(spectrumPixelValueSquareSum))));
                            for (int spectrumIndex = 0; spectrumIndex < this.spectra.length; spectrumIndex++) {
                                if (this.spectra[spectrumIndex].getName().equals(spec.getClassName())) {
                                    if (samAngle < this.threshold.get(spectrumIndex) && !setPixelColor) {
                                        if (this.threshold.get(spectrumIndex) < threshold) {
                                            samTile.setSample(x, y, this.classColor.get(spec.getClassName()));
                                            setPixelColor = true;
                                            threshold = this.threshold.get(spectrumIndex);
                                        }
                                    }
                                }
                            }
                        }
                        if (!setPixelColor) {
                            samTile.setSample(x, y, SpectralAngleMapperConstants.NO_DATA_VALUE);
                        }
                    }
                }
                checkForCancellation();
            }
            checkForCancellation();
            pm.worked(1);
        }finally {
            pm.done();
        }
    }

    private void parseThresholds() {
        StringTokenizer str = new StringTokenizer(this.thresholds, ", ");
        while (str.hasMoreElements()) {
            double thresholdValue = Double.parseDouble(str.nextToken().trim());
            this.threshold.add(thresholdValue);
        }
    }

    private void validateSpectra() {
        for (SpectrumInput aSpectra : spectra) {
            int xCounter = 0;
            int yCounter = 0;
            int xElements = aSpectra.getXPixelPolygonPositions().length;
            int yElements = aSpectra.getYPixelPolygonPositions().length;
            int[] xPositions = aSpectra.getXPixelPolygonPositions();
            int[] yPositions = aSpectra.getYPixelPolygonPositions();
            if (xElements == 0 || yElements == 0 || xElements != yElements) {
                throw new OperatorException("Invalid number of elements for spectrum " + aSpectra.getName());
            }
            for (int elementIndex = 0; elementIndex < xElements; elementIndex++) {
                if (xPositions[elementIndex] != -1) {
                    xCounter++;
                }
                if (yPositions[elementIndex] != -1) {
                    yCounter++;
                }
            }
            if ((xCounter == 0) || (yCounter == 0) || (xCounter != yCounter)) {
                throw new OperatorException("Invalid number of elements for spectrum " + aSpectra.getName());
            }
        }
    }

    private Product resample(Product source, int targetWidth, int targetHeight) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("referenceBandName", null);
        parameters.put("targetWidth", targetWidth);
        parameters.put("targetHeight", targetHeight);
        parameters.put("targetResolution", null);
        if (RESAMPLE_LOWEST.equals(this.resampleType)) {
            parameters.put("downsampling", this.downsamplingMethod != null ? this.downsamplingMethod : "First");
        } else if (RESAMPLE_HIGHEST.equals(this.resampleType)) {
            parameters.put("upsampling", this.upsamplingMethod != null ? this.upsamplingMethod : "Nearest");
        }
        return GPF.createProduct("Resample", parameters, source);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(SpectralAngleMapperOp.class);
        }

    }
}