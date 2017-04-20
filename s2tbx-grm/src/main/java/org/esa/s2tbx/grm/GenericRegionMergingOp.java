package org.esa.s2tbx.grm;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s2tbx.grm.segmentation.AbstractSegmenter;
import org.esa.s2tbx.grm.segmentation.BoundingBox;
import org.esa.s2tbx.grm.segmentation.tiles.*;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.*;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.math.MathUtils;

import javax.media.jai.JAI;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author  Jean Coravu
 */
@OperatorMetadata(
        alias = "GenericRegionMergingOp",
        version="1.0",
        category = "Optical/Thematic Land Processing",
        description = "The 'Generic Region Merging' operator computes the distinct regions from a product",
        authors = "Jean Coravu",
        copyright = "Copyright (C) 2017 by CS ROMANIA")
public class GenericRegionMergingOp extends Operator {
    private static final Logger logger = Logger.getLogger(GenericRegionMergingOp.class.getName());

    public static final String SPRING_MERGING_COST_CRITERION = "Spring";
    public static final String BAATZ_SCHAPE_MERGING_COST_CRITERION = "Baatz & Schape";
    public static final String FULL_LANDA_SCHEDULE_MERGING_COST_CRITERION = "Full Lamda Schedule";
    private static final String BEST_FITTING_REGION_MERGING_CRITERION = "Best Fitting";
    public static final String LOCAL_MUTUAL_BEST_FITTING_REGION_MERGING_CRITERION = "Local Mutual Best Fitting";

    @Parameter(label = "Merging cost criterion",
            description = "The method to compute the region merging.",
            valueSet = {SPRING_MERGING_COST_CRITERION, BAATZ_SCHAPE_MERGING_COST_CRITERION, FULL_LANDA_SCHEDULE_MERGING_COST_CRITERION})
    private String mergingCostCriterion;

    @Parameter(label = "Region merging criterion",
            description = "The method to check the region merging.",
            valueSet = {BEST_FITTING_REGION_MERGING_CRITERION, LOCAL_MUTUAL_BEST_FITTING_REGION_MERGING_CRITERION})
    private String regionMergingCriterion;

    @Parameter(label = "Total iterations for second segmentation", description = "The total number of iterations.")
    private int totalIterationsForSecondSegmentation;

    @Parameter(label = "Threshold", description = "The threshold.")
    private int threshold;

    @Parameter(label = "Spectral weight", description = "The spectral weight.")
    private float spectralWeight;

    @Parameter(label = "Shape weight", description = "The shape weight.")
    private float shapeWeight;

    @Parameter(label = "Source bands", description = "The source bands for the computation.", rasterDataNodeType = Band.class)
    private String[] sourceBandNames;

    @SourceProduct(alias = "source", description = "The source product.")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    private AbstractTileSegmenter tileSegmenter;
    private int tileCountX;
    private int tileCountY;
    private AtomicInteger processingTileCount;


    public static long availableMemory;
    public static long accumulatedMemory;
    public static boolean isFusion;
    public static Path temporaryFolderPath;
    public static TilesBidimensionalArray tilesBidimensionalArray;

    public GenericRegionMergingOp() {
    }

    @Override
    public void initialize() throws OperatorException {
        System.out.println("************* Operator "+getClass().getName()+" initialize ");

        //TODO Jean renmove
        this.mergingCostCriterion = BAATZ_SCHAPE_MERGING_COST_CRITERION;
        this.regionMergingCriterion = LOCAL_MUTUAL_BEST_FITTING_REGION_MERGING_CRITERION;
        this.totalIterationsForSecondSegmentation = 75;
        this.threshold = 2000;
        this.spectralWeight = 0.5f;
        this.shapeWeight = 0.5f;
//        this.sourceBandNames = new String[] {"red", "green", "blue"};
        this.sourceBandNames = new String[3];
        for (int i=0; i<3; i++) {
            this.sourceBandNames[i] = this.sourceProduct.getBandAt(i).getName();
        }

        if (this.mergingCostCriterion == null) {
            throw new OperatorException("Please specify the merging cost criterion.");
        }
        if (this.regionMergingCriterion == null) {
            throw new OperatorException("Please specify the region merging criterion.");
        }
        if (this.totalIterationsForSecondSegmentation == 0.0f) {
            throw new OperatorException("Please specify the total iterations for second segmentation.");
        }
        if (this.threshold == 0.0f) {
            throw new OperatorException("Please specify the threshold.");
        }
        if (BAATZ_SCHAPE_MERGING_COST_CRITERION.equalsIgnoreCase(this.mergingCostCriterion)) {
            if (this.spectralWeight == 0.0f) {
                throw new OperatorException("Please specify the spectral weight.");
            }
            if (this.shapeWeight == 0.0f) {
                throw new OperatorException("Please specify the shape weight.");
            }
        }
        if (this.sourceBandNames == null || this.sourceBandNames.length == 0) {
            throw new OperatorException("Please select at least one band.");
        }

        this.processingTileCount = new AtomicInteger(0);

        createTargetProduct();
        createTileSegmenter();
        computeTileCount();

        //TODO Jean remove
        Logger.getLogger("org.esa.s2tbx.grm").setLevel(Level.FINE);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        Rectangle targetRectangle = targetTile.getRectangle();
        int currentTileNumber = this.processingTileCount.incrementAndGet();
        int totalTileCount = this.tileCountX * this.tileCountY;

        int imageWidth = this.tileSegmenter.getImageWidth();
        int imageHeight = this.tileSegmenter.getImageHeight();
        int tileWidth = this.tileSegmenter.getTileWidth();
        int tileHeight = this.tileSegmenter.getTileHeight();
        int tileMargin = this.tileSegmenter.computeTileMargin();

        if (currentTileNumber == 1) {
            long startTime = System.currentTimeMillis();
            if (logger.isLoggable(Level.FINE)) {
                int firstNumberOfIterations = this.tileSegmenter.getIterationsForEachFirstSegmentation();
                logger.log(Level.FINE, ""); // add an empty line
                logger.log(Level.FINE, "Start Segmentation: image width: " +imageWidth+", image height: "+imageHeight+", tile width: "+tileWidth+", tile height: "+tileHeight+", margin: "+tileMargin+", first number of iterations: "+firstNumberOfIterations+", start time: "+new Date(startTime));
            }
        }

        processTile(targetRectangle, totalTileCount);
    }

    private void processTile(Rectangle targetRectangle, int totalTileCount) {
        try {
            ProcessingTile currentTile = this.tileSegmenter.buildTile(targetRectangle.x, targetRectangle.y, targetRectangle.width, targetRectangle.height);

            int tileColumnIndex = this.tileSegmenter.computeTileColumnIndex(currentTile);
            int tileRowIndex = this.tileSegmenter.computeTileRowIndex(currentTile);
            int tileMargin = this.tileSegmenter.computeTileMargin();
            if (logger.isLoggable(Level.FINE)) {
                int firstNumberOfIterations = this.tileSegmenter.getIterationsForEachFirstSegmentation();
                logger.log(Level.FINE, ""); // add an empty line
                logger.log(Level.FINE, "Compute tile: row index: "+tileRowIndex+", column index: "+tileColumnIndex+", margin: "+tileMargin+", bounds: [x=" +targetRectangle.x+", y="+targetRectangle.y+", width="+targetRectangle.width+", height="+targetRectangle.height+"], first number of iterations: "+firstNumberOfIterations);
            }

            ProcessingTile oldTile = this.tileSegmenter.addTile(tileRowIndex, tileColumnIndex, currentTile);
            if (oldTile == null) {
                Tile[] sourceTiles = getSourceTiles(currentTile.getRegion());

                try {
                    this.tileSegmenter.runOneTileFirstSegmentation(sourceTiles, currentTile);
                } catch (Exception ex) {
                    throw new OperatorException(ex);
                }
            } else {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, ""); // add an empty line
                    logger.log(Level.FINE, "Tile already computed: row index: "+tileRowIndex+", column index: "+tileColumnIndex+", margin: "+tileMargin+", bounds: [x=" +targetRectangle.x+", y="+targetRectangle.y+", width="+targetRectangle.width+", height="+targetRectangle.height+"]");
                }
            }
        } finally {
        }
    }

    public Class<?> getTileSegmenterClass() {
        return this.tileSegmenter.getClass();
    }

    public AbstractSegmenter runSegmentation() throws IOException, IllegalAccessException {
        return null;
    }

    private void computeTileCount() {
        Dimension tileSize = this.targetProduct.getPreferredTileSize();
        int rasterHeight = this.targetProduct.getSceneRasterHeight();
        int rasterWidth = this.targetProduct.getSceneRasterWidth();
        Rectangle boundary = new Rectangle(rasterWidth, rasterHeight);
        this.tileCountX = MathUtils.ceilInt(boundary.width / (double) tileSize.width);
        this.tileCountY = MathUtils.ceilInt(boundary.height / (double) tileSize.height);
    }

    private void createTileSegmenter() {
        boolean fastSegmentation = false;
        if (BEST_FITTING_REGION_MERGING_CRITERION.equalsIgnoreCase(this.regionMergingCriterion)) {
            fastSegmentation = true;
        } else if (BEST_FITTING_REGION_MERGING_CRITERION.equalsIgnoreCase(this.regionMergingCriterion)) {
            fastSegmentation = false;
        }

        try {
            Dimension imageSize = new Dimension(this.targetProduct.getSceneRasterWidth(), this.targetProduct.getSceneRasterHeight());
            Dimension tileSize = this.targetProduct.getPreferredTileSize();
            if (SPRING_MERGING_COST_CRITERION.equalsIgnoreCase(this.mergingCostCriterion)) {
                this.tileSegmenter = new SpringTileSegmenter(imageSize, tileSize, totalIterationsForSecondSegmentation, threshold, fastSegmentation);
            } else if (BAATZ_SCHAPE_MERGING_COST_CRITERION.equalsIgnoreCase(this.mergingCostCriterion)) {
                this.tileSegmenter = new BaatzSchapeTileSegmenter(imageSize, tileSize, totalIterationsForSecondSegmentation, threshold, fastSegmentation, spectralWeight, shapeWeight);
            } else if (FULL_LANDA_SCHEDULE_MERGING_COST_CRITERION.equalsIgnoreCase(this.mergingCostCriterion)) {
                this.tileSegmenter = new FullLambdaScheduleTileSegmenter(imageSize, tileSize, totalIterationsForSecondSegmentation, threshold, fastSegmentation);
            }
        } catch (Exception ex) {
            throw new OperatorException(ex);
        }
    }

    private void createTargetProduct() {
        int sceneWidth = this.sourceProduct.getSceneRasterWidth();
        int sceneHeight = this.sourceProduct.getSceneRasterHeight();
        Dimension tileSize = JAI.getDefaultTileSize();

        //TODO Jean remove
        //tileSize = new Dimension(1700, 1700);

        this.targetProduct = new Product(this.sourceProduct.getName() + "_grm", this.sourceProduct.getProductType(), sceneWidth, sceneHeight);
        this.targetProduct.setPreferredTileSize(tileSize);

        Band targetBand = new Band("band_1", ProductData.TYPE_INT32, sceneWidth, sceneHeight);
        this.targetProduct.addBand(targetBand);
    }

    private Tile[] getSourceTiles(BoundingBox tileRegion) {
        Tile[] sourceTiles = new Tile[this.sourceBandNames.length];
        Rectangle rectangleToRead = new Rectangle(tileRegion.getLeftX(), tileRegion.getTopY(), tileRegion.getWidth(), tileRegion.getHeight());
        for (int i=0; i<this.sourceBandNames.length; i++) {
            Band band = this.sourceProduct.getBand(this.sourceBandNames[i]);
            sourceTiles[i] = getSourceTile(band, rectangleToRead);
        }
        return sourceTiles;
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(GenericRegionMergingOp.class);
        }
    }
}