package org.esa.s2tbx.dataio.muscate;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s2tbx.commons.FilePathInputStream;
import org.esa.s2tbx.dataio.VirtualDirEx;
import org.esa.s2tbx.dataio.readers.BaseProductReaderPlugIn;
import org.esa.s2tbx.dataio.s2.S2BandAnglesGrid;
import org.esa.s2tbx.dataio.s2.S2BandAnglesGridByDetector;
import org.esa.s2tbx.dataio.s2.S2BandConstants;
import org.esa.s2tbx.dataio.s2.ortho.S2AnglesGeometry;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.metadata.XmlMetadataParser;
import org.esa.snap.core.metadata.XmlMetadataParserFactory;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dataio.ImageRegistryUtils;
import org.esa.snap.dataio.geotiff.GeoTiffImageReader;
import org.esa.snap.dataio.geotiff.GeoTiffProductReader;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.xml.sax.SAXException;

import javax.imageio.spi.ImageInputStreamSpi;
import javax.media.jai.PlanarImage;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.awt.image.DataBuffer.TYPE_FLOAT;
import static org.esa.snap.utils.DateHelper.parseDate;

/**
 * Created by obarrile on 26/01/2017.
 */
public class MuscateProductReader extends AbstractProductReader implements S2AnglesGeometry {

    private static final Logger logger = Logger.getLogger(MuscateProductReader.class.getName());

    static {
        XmlMetadataParserFactory.registerParser(MuscateMetadata.class, new XmlMetadataParser<>(MuscateMetadata.class));
    }

    private VirtualDirEx productDirectory;
    private MuscateMetadata metadata;
    private List<GeoTiffImageReader> bandImageReaders;
    private ImageInputStreamSpi imageInputStreamSpi;

    public MuscateProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);

        this.imageInputStreamSpi = ImageRegistryUtils.registerImageInputStreamSpi();
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        boolean success = false;
        try {
            Path productPath = BaseProductReaderPlugIn.convertInputToPath(super.getInput());
            this.productDirectory = VirtualDirEx.build(productPath, false, false);

            String[] filePaths = this.productDirectory.listAllFiles();
            this.metadata = readMetadata(this.productDirectory, filePaths);

            ProductSubsetDef subsetDef = getSubsetDef();
            Dimension defaultProductSize = new Dimension(metadata.getRasterWidth(), metadata.getRasterHeight());
            GeoCoding productDefaultGeoCoding = null;
            Rectangle productBounds;

            ProductFilePathsHelper filePathsHelper = new ProductFilePathsHelper(filePaths, this.productDirectory.getFileSystemSeparator());

            boolean isMultiSize = isMultiSize(filePathsHelper);
            if (subsetDef == null || subsetDef.getSubsetRegion() == null) {
                productBounds = new Rectangle(0, 0, defaultProductSize.width, defaultProductSize.height);
            } else {
                productDefaultGeoCoding = metadata.buildCrsGeoCoding(null);
                productBounds = subsetDef.getSubsetRegion().computeProductPixelRegion(productDefaultGeoCoding, defaultProductSize.width, defaultProductSize.height, isMultiSize);
            }
            if (productBounds.isEmpty()) {
                throw new IllegalStateException("Empty product bounds.");
            }

            // create product
            Product product = new Product(this.metadata.getProductName(), MuscateConstants.MUSCATE_FORMAT_NAMES[0], productBounds.width, productBounds.height);
            product.setDescription(metadata.getDescription());
            if (subsetDef == null || !subsetDef.isIgnoreMetadata()) {
                product.getMetadataRoot().addElement(metadata.getRootElement());
            }
            product.setFileLocation(productPath.toFile());
            product.setSceneGeoCoding(metadata.buildCrsGeoCoding(productBounds));
            product.setNumResolutionsMax(metadata.getGeoPositions().size());
            product.setAutoGrouping(buildGroupPattern());
            product.setStartTime(parseDate(metadata.getAcquisitionDate(), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
            product.setEndTime(parseDate(metadata.getAcquisitionDate(), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));

            this.bandImageReaders = new ArrayList<>();

            // add bands
            for (MuscateImage muscateImage : metadata.getImages()) {
                if (muscateImage == null || muscateImage.nature == null) {
                    logger.warning(String.format("Unable to add an image with a null nature to the product: %s", product.getName()));
                } else {
                    List<Band> imageBands = readImageBands(muscateImage, defaultProductSize, metadata, filePathsHelper, productDefaultGeoCoding, isMultiSize);
                    for (Band band : imageBands) {
                        product.addBand(band);
                    }
                }
            }

            List<Band> angleBands = readAngleBands(productBounds, defaultProductSize, metadata, subsetDef);
            for (Band band : angleBands) {
                product.addBand(band);
            }

            // add masks
            for (MuscateMask muscateMask : metadata.getMasks()) {
                if (muscateMask == null || muscateMask.nature == null) {
                    logger.warning(String.format("Unable to add a mask with a null nature to the product: %s", product.getName()));
                } else {
                    readMaskBands(product, productDefaultGeoCoding, defaultProductSize, metadata, filePathsHelper, muscateMask, isMultiSize);
                }
            }

            success = true;

            return product;
        } catch (RuntimeException | IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException(exception);
        } finally {
            if (!success) {
                closeResources();
            }
        }
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY,
                                          Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm)
            throws IOException {
        // do nothing
    }

    @Override
    public S2BandAnglesGridByDetector[] getViewingIncidenceAnglesGrids(int bandId, int detectorId) {
        if (this.metadata == null) {
            return null;
        }
        MuscateMetadata.AnglesGrid[] viewingAnglesList = metadata.getViewingAnglesGrid();
        S2BandConstants bandConstants = S2BandConstants.getBand(bandId);

        for (MuscateMetadata.AnglesGrid viewingAngles : viewingAnglesList) {
            if (viewingAngles.getBandId().equals(bandConstants.getPhysicalName()) && Integer.parseInt(viewingAngles.getDetectorId()) == detectorId) {
                S2BandAnglesGridByDetector[] bandAnglesGridByDetector = new S2BandAnglesGridByDetector[2];
                bandAnglesGridByDetector[0] = new S2BandAnglesGridByDetector("view_zenith", bandConstants, detectorId, viewingAngles.getWidth(), viewingAngles.getHeight(), (float) metadata.getUpperLeft().x, (float) metadata.getUpperLeft().y, viewingAngles.getResolutionX(), viewingAngles.getResolutionY(), viewingAngles.getZenith());
                bandAnglesGridByDetector[1] = new S2BandAnglesGridByDetector("view_azimuth", bandConstants, detectorId, viewingAngles.getWidth(), viewingAngles.getHeight(), (float) metadata.getUpperLeft().x, (float) metadata.getUpperLeft().y, viewingAngles.getResolutionX(), viewingAngles.getResolutionY(), viewingAngles.getAzimuth());
                return bandAnglesGridByDetector;
            }
        }

        return null;
    }

    @Override
    public S2BandAnglesGrid[] getSunAnglesGrid() {
        if (this.metadata == null) {
            return null;
        }
        MuscateMetadata.AnglesGrid sunAngles = metadata.getSunAnglesGrid();

        S2BandAnglesGrid[] bandAnglesGrid = new S2BandAnglesGrid[2];
        bandAnglesGrid[0] = new S2BandAnglesGrid("sun_zenith", null, sunAngles.getWidth(), sunAngles.getHeight(), (float) metadata.getUpperLeft().x, (float) metadata.getUpperLeft().y, sunAngles.getResolutionX(), sunAngles.getResolutionY(), sunAngles.getZenith());
        bandAnglesGrid[1] = new S2BandAnglesGrid("sun_azimuth", null, sunAngles.getWidth(), sunAngles.getHeight(), (float) metadata.getUpperLeft().x, (float) metadata.getUpperLeft().y, sunAngles.getResolutionX(), sunAngles.getResolutionY(), sunAngles.getAzimuth());
        return bandAnglesGrid;
    }

    @Override
    public void close() throws IOException {
        super.close();

        closeResources();
    }

    private void closeResources() {
        try {
            if (this.bandImageReaders != null) {
                for (GeoTiffImageReader geoTiffImageReader : this.bandImageReaders) {
                    try {
                        geoTiffImageReader.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                this.bandImageReaders.clear();
                this.bandImageReaders = null;
            }
        } finally {
            try {
                if (this.imageInputStreamSpi != null) {
                    ImageRegistryUtils.deregisterImageInputStreamSpi(this.imageInputStreamSpi);
                    this.imageInputStreamSpi = null;
                }
            } finally {
                if (this.productDirectory != null) {
                    this.productDirectory.close();
                    this.productDirectory = null;
                }
            }
        }
        System.gc();
    }

    private List<Band> readImageBands(MuscateImage muscateImage, Dimension defaultProductSize, MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, GeoCoding productDefaultGeoCoding, boolean isMultiSize)
            throws Exception {

        List<Band> productBands = new ArrayList<>();
        //TODO Read together AOT and WVC? they should be in the same tif file
        if (muscateImage.nature.equals(MuscateImage.AEROSOL_OPTICAL_THICKNESS_IMAGE)) {
            for (String tiffImageRelativeFilePath : muscateImage.getImageFiles()) {
                Band geoTiffBand = readAOTImageBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, metadata, filePathsHelper, isMultiSize);
                if (geoTiffBand != null) {
                    productBands.add(geoTiffBand);
                }
            }
        } else if (muscateImage.nature.equals(MuscateImage.FLAT_REFLECTANCE_IMAGE)) {
            for (String tiffImageRelativeFilePath : muscateImage.getImageFiles()) {
                BandNameCallback bandNameCallback = buildFlatReflectanceImageBandNameCallback();
                Band geoTiffBand = readReflectanceImageBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, metadata, filePathsHelper, bandNameCallback, isMultiSize);
                if (geoTiffBand != null) {
                    String bandId = getBandFromFileName(tiffImageRelativeFilePath);
                    geoTiffBand.setDescription(String.format("Ground reflectance with the correction of slope effects, band %s", bandId));
                    productBands.add(geoTiffBand);
                }
            }
        } else if (muscateImage.nature.equals(MuscateImage.SURFACE_REFLECTANCE_IMAGE)) {
            for (String tiffImageRelativeFilePath : muscateImage.getImageFiles()) {
                BandNameCallback bandNameCallback = buildSurfaceReflectanceImageBandNameCallback();
                Band geoTiffBand = readReflectanceImageBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, metadata, filePathsHelper, bandNameCallback, isMultiSize);
                if (geoTiffBand != null) {
                    String bandId = getBandFromFileName(tiffImageRelativeFilePath);
                    geoTiffBand.setDescription(String.format("Ground reflectance without the correction of slope effects, band %s", bandId));
                    productBands.add(geoTiffBand);
                }
            }
        } else if (muscateImage.nature.equals(MuscateImage.WATER_VAPOR_CONTENT_IMAGE)) {
            for (String tiffImageRelativeFilePath : muscateImage.getImageFiles()) {
                Band geoTiffBand = readWVCImageBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, metadata, filePathsHelper, isMultiSize);
                if (geoTiffBand != null) {
                    productBands.add(geoTiffBand);
                }
            }
        } else {
            logger.warning(String.format("Unable to add image. Unknown nature: %s", muscateImage.nature));
        }
        return productBands;
    }

    private void readMaskBands(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, MuscateMetadata metadata,
                               ProductFilePathsHelper filePathsHelper, MuscateMask muscateMask, boolean isMultiSize)
            throws Exception {

        // the mask depends on the version
        float versionFloat = metadata.getVersion();// (version == null) ? 0.0f : Float.valueOf(version);
        Set<String> addedFiles = new HashSet<>();

        if (muscateMask.nature.equals(MuscateMask.AOT_INTERPOLATION_MASK)) {
            int bitNumber = 0;
            if (versionFloat > MuscateMask.AOT_INTERPOLATION_MASK_VERSION) {
                bitNumber = 1; // after this version the bitNumber has changed
            }
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readAOTMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, bitNumber, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.DETAILED_CLOUD_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add(muscateMaskFile.path)) {
                    readCloudMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.CLOUD_MASK)) {
            if (versionFloat < MuscateMask.CLOUD_MASK_VERSION) {
                for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                    addedFiles.add(muscateMaskFile.path);
                    readCloudMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            } else {
                for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                    addedFiles.add(muscateMaskFile.path);
                    readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Cloud, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.CLOUD_SHADOW_MASK)) {
            if (versionFloat < MuscateMask.CLOUD_SHADOW_MASK_VERSION) {
                // in some old products the Nature is Cloud_Shadow instead of Geophysics. Perhaps an error?
                for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                    addedFiles.add(muscateMaskFile.path);
                    readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            } else {
                for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                    addedFiles.add(muscateMaskFile.path);
                    readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Cloud_Shadow, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.EDGE_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add(muscateMaskFile.path)) {
                    readEdgeMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.SATURATION_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add(muscateMaskFile.path)) {
                    readSaturationMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.GEOPHYSICS_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add(muscateMaskFile.path)) {
                    readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.DETECTOR_FOOTPRINT_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add( muscateMaskFile.path)) {
                    readDetectorFootprintMask(product, productDefaultGeoCoding, defaultProductSize,  muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.DEFECTIVE_PIXEL_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                if (addedFiles.add(muscateMaskFile.path)) {
                    readDefectivePixelMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
                }
            }
        } else if (muscateMask.nature.equals(MuscateMask.HIDDEN_SURFACE_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Hidden_Surface, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.SNOW_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Snow, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.SUN_TOO_LOW_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Sun_Too_Low, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.TANGENT_SUN_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Tangent_Sun, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.TOPOGRAPHY_SHADOW_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Topography_Shadow, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.WATER_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readGeophysicsMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT.Water, isMultiSize);
            }
        } else if (muscateMask.nature.equals(MuscateMask.WVC_INTERPOLATION_MASK)) {
            for (MuscateMaskFile muscateMaskFile : muscateMask.getMaskFiles()) {
                addedFiles.add(muscateMaskFile.path);
                readWVCMask(product, productDefaultGeoCoding, defaultProductSize, muscateMaskFile.path, metadata, filePathsHelper, isMultiSize);
            }
        } else {
            logger.warning(String.format("Unable to add mask. Unknown nature: %s", muscateMask.nature));
        }
    }

    private boolean isMaskAccepted(String maskName) {
        ProductSubsetDef subsetDef = getSubsetDef();
        return (subsetDef == null || subsetDef.isNodeAccepted(maskName));
    }

    private GeoTiffBandResult readGeoTiffProductBand(GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                                                     int bandIndex, MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper,
                                                     BandNameCallback bandNameCallback, boolean isMultiSize, Double noDataValue)
                                                     throws Exception {

        String tiffImageFilePath = filePathsHelper.computeImageRelativeFilePath(this.productDirectory, tiffImageRelativeFilePath);
        GeoTiffBandResult geoTiffBandResult = null;
        boolean success = false;
        GeoTiffImageReader geoTiffImageReader = GeoTiffImageReader.buildGeoTiffImageReader(this.productDirectory.getBaseFile().toPath(), tiffImageFilePath);
        try {
            // the tiff image exists and read the data
            int defaultBandWidth = geoTiffImageReader.getImageWidth();
            int defaultBandHeight = geoTiffImageReader.getImageHeight();
            MuscateMetadata.Geoposition geoPosition = metadata.getGeoposition(defaultBandWidth, defaultBandHeight);
            if (geoPosition == null) {
                logger.warning(String.format("Unrecognized geometry of image %s, it will not be added to the product.", tiffImageRelativeFilePath));
            } else {
                ProductSubsetDef subsetDef = getSubsetDef();
                String bandName = bandNameCallback.buildBandName(geoPosition, tiffImageRelativeFilePath);// bandNamePrefix + geoPosition.id;
                if (subsetDef == null || subsetDef.isNodeAccepted(bandName)) {
                    GeoTiffProductReader geoTiffProductReader = new GeoTiffProductReader(getReaderPlugIn(), null);
                    Rectangle bandBounds;
                    if (subsetDef == null || subsetDef.getSubsetRegion() == null) {
                        bandBounds = new Rectangle(defaultBandWidth, defaultBandHeight);
                    } else {
                        GeoCoding bandDefaultGeoCoding = GeoTiffProductReader.readGeoCoding(geoTiffImageReader, null);
                        bandBounds = subsetDef.getSubsetRegion().computeBandPixelRegion(productDefaultGeoCoding, bandDefaultGeoCoding, defaultProductSize.width, defaultProductSize.height, defaultBandWidth, defaultBandHeight, isMultiSize);
                    }
                    if (!bandBounds.isEmpty()) {
                        // there is an intersection
                        Product geoTiffProduct = geoTiffProductReader.readProduct(geoTiffImageReader, null, bandBounds, noDataValue);
                        Band geoTiffBand = geoTiffProduct.getBandAt(bandIndex);
                        geoTiffBand.setName(bandName);
                        geoTiffBandResult = new GeoTiffBandResult(geoTiffBand, geoPosition);
                        success = true;
                    }
                }
            }
        } catch (IOException e) {
            logger.warning(String.format("Unable to get band %d of the product: %s", bandIndex, tiffImageRelativeFilePath));
        } catch (Exception e) {
            throw e;
        } finally {
            if (success) {
                this.bandImageReaders.add(geoTiffImageReader);
            } else {
                geoTiffImageReader.close();
            }
        }
        return geoTiffBandResult;
    }

    private Band readAOTImageBand(GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                                  MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                                  throws Exception {

        double noDataValue = metadata.getAOTNoDataValue();
        BandNameCallback bandNameCallback = buildAOTImageBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 1,
                                                                     metadata, filePathsHelper, bandNameCallback, isMultiSize, noDataValue);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            geoTiffBand.setNoDataValue(noDataValue);
            geoTiffBand.setNoDataValueUsed(true);
            geoTiffBand.setScalingFactor(1.0d / metadata.getAOTQuantificationValue());
            geoTiffBand.setScalingOffset(0.0d);
            geoTiffBand.setDescription(String.format("Aerosol Optical Thickness at %.0fm resolution", geoTiffBandResult.getGeoPosition().xDim));
            return geoTiffBand;
        }
        return null;
    }

    private Band readWVCImageBand(GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath, MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
            throws Exception {

        double noDataValue = metadata.getWVCNoDataValue();
        BandNameCallback bandNameCallback = buildWVCImageBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, bandNameCallback, isMultiSize, noDataValue);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            geoTiffBand.setNoDataValue(noDataValue);
            geoTiffBand.setNoDataValueUsed(true);
            geoTiffBand.setScalingFactor(1.0d / metadata.getWVCQuantificationValue());
            geoTiffBand.setScalingOffset(0.0d);
            geoTiffBand.setUnit("cm"); //TODO verify
            geoTiffBand.setDescription(String.format("Water vapor content at %.0fm resolution in %s", geoTiffBandResult.getGeoPosition().xDim, geoTiffBand.getUnit()));
            return geoTiffBand;
        }
        return null;
    }

    private Band readReflectanceImageBand(GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath, MuscateMetadata metadata,
                                          ProductFilePathsHelper filePathsHelper, BandNameCallback bandNameCallback, boolean isMultiSize)
            throws Exception {

        double noDataValue = metadata.getReflectanceNoDataValue();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, bandNameCallback, isMultiSize, noDataValue);
        if (geoTiffBandResult != null) {
            String bandId = getBandFromFileName(tiffImageRelativeFilePath);
            Band geoTiffBand = geoTiffBandResult.getBand();
            geoTiffBand.setNoDataValue(noDataValue);
            geoTiffBand.setNoDataValueUsed(true);
            geoTiffBand.setSpectralWavelength(metadata.getCentralWavelength(bandId)); //not available in metadata
            geoTiffBand.setScalingFactor(1.0d / metadata.getReflectanceQuantificationValue());
            geoTiffBand.setScalingOffset(0.0d);
            return geoTiffBand;
        }
        return null;
    }

    private void readAOTMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                             MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, int bitNumber, boolean isMultiSize)
                             throws Exception {

        BandNameCallback maskBandNameCallback = buildAOTMaskBandNamesCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            if (!product.containsBand(geoTiffBand.getName())) {
                geoTiffBand.setNoDataValueUsed(false);
                geoTiffBand.setScalingFactor(1);
                geoTiffBand.setScalingOffset(0);
                geoTiffBand.setDescription("Interpolated pixels mask");

                product.addBand(geoTiffBand);
            }
            String maskName = computeAOTMaskName(geoTiffBandResult.getGeoPosition()); // "AOT_Interpolation_Mask_" + geoTiffBandResult.getGeoPosition().id;
            if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                Mask mask = buildMaskFromBand(geoTiffBand, maskName, "Interpolated AOT pixels mask", String.format("bit_set(%s,%d)", geoTiffBand.getName(), bitNumber), Color.BLUE);
                product.addMask(mask);
            }
        }
    }

    private void readWVCMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                             MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                             throws Exception {

        BandNameCallback maskBandNameCallback = buildWVCMaskNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            if (!product.containsBand(geoTiffBand.getName())) {
                geoTiffBand.setNoDataValueUsed(false);
                geoTiffBand.setScalingFactor(1);
                geoTiffBand.setScalingOffset(0);
                geoTiffBand.setDescription("Interpolated pixels mask");

                product.addBand(geoTiffBand);
            }
            String maskName = computeWVCMaskName(geoTiffBandResult.getGeoPosition()); // "WVC_Interpolation_Mask_" + geoTiffBandResult.getGeoPosition().id;
            if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                Mask mask = buildMaskFromBand(geoTiffBand, maskName, "Interpolated WVC pixels mask", String.format("bit_set(%s,0)", geoTiffBand.getName()), Color.BLUE);
                product.addMask(mask);
            }
        }
    }

    private void readEdgeMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                              MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                              throws Exception {

        BandNameCallback maskBandNameCallback = buildEdgeMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            geoTiffBand.setNoDataValueUsed(false);
            geoTiffBand.setScalingFactor(1);
            geoTiffBand.setScalingOffset(0);
            geoTiffBand.setDescription("Edge mask");

            product.addBand(geoTiffBand);

            String maskName = computeEdgeMaskName(geoTiffBandResult.getGeoPosition()); // "edge_mask_" + geoTiffBandResult.getGeoPosition().id;
            if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                Mask mask = buildMaskFromBand(geoTiffBand, maskName, "Edge mask", String.format("bit_set(%s,0)", geoTiffBand.getName()), Color.GREEN);
                product.addMask(mask);
            }
        }
    }

    private void readSaturationMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                                    MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                                    throws Exception {

        BandNameCallback maskBandNameCallback = buildSaturationMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            geoTiffBand.setNoDataValueUsed(false);
            geoTiffBand.setScalingFactor(1);
            geoTiffBand.setScalingOffset(0);
            geoTiffBand.setDescription("Saturation mask coded over 8 bits, 1 bit per spectral band (number of useful bits = number of spectral bands)");
            product.addBand(geoTiffBand);

            List<String> bands = metadata.getBandNames(geoTiffBandResult.getGeoPosition().id);
            for (int bitCount=0; bitCount<bands.size(); bitCount++) {
                String bandId = bands.get(bitCount);
                String maskName = computeSaturationMaskName(bandId);
                if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                    Mask mask = buildMaskFromBand(geoTiffBand, maskName, String.format("Saturation mask of band %s", bandId), String.format("bit_set(%s,%d)", geoTiffBand.getName(), bitCount), Color.RED);
                    product.addMask(mask);
                }
            }
        }
    }

    private void readDetectorFootprintMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                                           MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                                           throws Exception {

        BandNameCallback maskBandNameCallback = buildDetectorFootprintMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();// readGeoTiffProductBand(tiffImageRelativeFilePath, 0);
            geoTiffBand.setNoDataValueUsed(false);
            geoTiffBand.setDescription("Detector footprint");
            product.addBand(geoTiffBand);

            // add masks
            ColorIterator.reset();
            String[] orderedBandNames = metadata.getOrderedBandNames(geoTiffBandResult.getGeoPosition().id);
            for (int i = 0; i < orderedBandNames.length; i++) {
                String maskName = computeDetectorFootprintMaskName(tiffImageRelativeFilePath, orderedBandNames[i]);
                if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                    Mask mask = buildMaskFromBand(geoTiffBand, maskName, "Detector footprint", String.format("bit_set(%s,%d)", geoTiffBand.getName(), i), ColorIterator.next());
                    product.addMask(mask);
                }
            }
        }
    }

    private void readCloudMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                               MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                               throws Exception {

        BandNameCallback maskBandNameCallback = buildCloudMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNameCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            // add band to product if it hasn't been added yet
            if (!product.containsBand(geoTiffBand.getName())) {
                geoTiffBand.setNoDataValueUsed(false);
                geoTiffBand.setScalingFactor(1);
                geoTiffBand.setScalingOffset(0);
                geoTiffBand.setDescription("Cloud mask computed by MACCS software, made of 1 band coded over 8 useful bits");
                product.addBand(geoTiffBand);
            }

            // add masks
            ColorIterator.reset();
            String bandName = geoTiffBand.getName();
            MuscateMetadata.Geoposition geoposition = geoTiffBandResult.getGeoPosition();

            String maskName0 = computeCloudMaskAllName(geoposition);
            if (isMaskAccepted(maskName0) && !product.getMaskGroup().contains(maskName0)) {
                Mask mask0 = buildMaskFromBand(geoTiffBand, computeCloudMaskAllName(geoposition), "Result of a 'logical OR' for all the cloud and shadow maks", String.format("bit_set(%s,0)", bandName), ColorIterator.next());
                product.addMask(mask0);
            }

            String maskName1 = computeCloudMaskAllCloudName(geoposition);
            if (isMaskAccepted(maskName1) && !product.getMaskGroup().contains(maskName1)) {
                Mask mask1 = buildMaskFromBand(geoTiffBand, maskName1, "Result of a 'logical OR' for all the cloud masks", String.format("bit_set(%s,1)", bandName), ColorIterator.next());
                product.addMask(mask1);
            }

            String maskName2 = computeCloudMaskReflectanceName(geoposition);
            if (isMaskAccepted(maskName2) && !product.getMaskGroup().contains(maskName2)) {
                Mask mask2 = buildMaskFromBand(geoTiffBand, maskName2, "Cloud mask identified by a reflectance threshold", String.format("bit_set(%s,2)", bandName), ColorIterator.next());
                product.addMask(mask2);
            }

            String maskName3 = computeCloudMaskReflectanceVarianceName(geoposition);
            if (isMaskAccepted(maskName3) && !product.getMaskGroup().contains(maskName3)) {
                Mask mask3 = buildMaskFromBand(geoTiffBand, maskName3, "Cloud mask identified by a threshold on reflectance variance", String.format("bit_set(%s,3)", bandName), ColorIterator.next());
                product.addMask(mask3);
            }

            String maskName4 = computeCloudMaskExtensionName(geoposition);
            if (isMaskAccepted(maskName4) && !product.getMaskGroup().contains(maskName4)) {
                Mask mask4 = buildMaskFromBand(geoTiffBand, maskName4, "Cloud mask identified by the extension of cloud masks", String.format("bit_set(%s,4)", bandName), ColorIterator.next());
                product.addMask(mask4);
            }

            String maskName5 = computeCloudMaskInsideShadowName(geoposition);
            if (isMaskAccepted(maskName5) && !product.getMaskGroup().contains(maskName5)) {
                Mask mask5 = buildMaskFromBand(geoTiffBand, maskName5, "Shadow mask of clouds inside the image", String.format("bit_set(%s,5)", bandName), ColorIterator.next());
                product.addMask(mask5);
            }

            String maskName6 = computeCloudMaskOutsideShadowName(geoposition);
            if (isMaskAccepted(maskName6) && !product.getMaskGroup().contains(maskName6)) {
                Mask mask6 = buildMaskFromBand(geoTiffBand, computeCloudMaskOutsideShadowName(geoposition), "Shadow mask of clouds outside the image", String.format("bit_set(%s,6)", bandName), ColorIterator.next());
                product.addMask(mask6);
            }

            String maskName7 = computeCloudMaskCirrusName(geoposition);
            if (isMaskAccepted(maskName7) && !product.getMaskGroup().contains(maskName7)) {
                Mask mask7 = buildMaskFromBand(geoTiffBand, computeCloudMaskCirrusName(geoposition), "Cloud mask identified with the cirrus spectral band", String.format("bit_set(%s,7)", bandName), ColorIterator.next());
                product.addMask(mask7);
            }
        }
    }

    private void readGeophysicsMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath, MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                                    throws Exception {

        BandNameCallback maskBandNamesCallback = buildGeophysicsMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNamesCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();// readGeoTiffProductBand(tiffImageRelativeFilePath, 0);
            if (!product.containsBand(geoTiffBand.getName())) {
                geoTiffBand.setNoDataValueUsed(false);
                geoTiffBand.setScalingFactor(1);
                geoTiffBand.setScalingOffset(0);
                geoTiffBand.setDescription("Geophysical mask of level 2, made of 1 band coded over 8 useful bits");
                product.addBand(geoTiffBand);
            }

            for (MuscateConstants.GEOPHYSICAL_BIT geophysicalBit : MuscateConstants.GEOPHYSICAL_BIT.values()) {
                String maskName = computeGeographicMaskName(geophysicalBit, geoTiffBandResult.getGeoPosition());
                if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                    Mask mask = buildGeophysicsMask(geophysicalBit, geoTiffBand, maskName);
                    product.addMask(mask);
                }
            }
        }
    }

    private void readGeophysicsMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize, String tiffImageRelativeFilePath,
                                    MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, MuscateConstants.GEOPHYSICAL_BIT geophysicalBit, boolean isMultiSize)
                                    throws Exception {

        BandNameCallback maskBandNamesCallback = buildGeophysicsMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNamesCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();
            if (!product.containsBand(geoTiffBand.getName())) {
                geoTiffBand.setNoDataValueUsed(false);
                geoTiffBand.setScalingFactor(1);
                geoTiffBand.setScalingOffset(0);
                geoTiffBand.setDescription("Geophysical mask of level 2, made of 1 band coded over 8 useful bits");
                product.addBand(geoTiffBand);
            }
            String maskName = computeGeographicMaskName(geophysicalBit, geoTiffBandResult.getGeoPosition());
            if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                Mask mask = buildGeophysicsMask(geophysicalBit, geoTiffBandResult.getBand(), maskName);
                product.addMask(mask);
            }
        }
    }

    private void readDefectivePixelMask(Product product, GeoCoding productDefaultGeoCoding, Dimension defaultProductSize,
                                        String tiffImageRelativeFilePath, MuscateMetadata metadata, ProductFilePathsHelper filePathsHelper, boolean isMultiSize)
                                        throws Exception {

        BandNameCallback maskBandNamesCallback = buildDefectivePixelMaskBandNameCallback();
        GeoTiffBandResult geoTiffBandResult = readGeoTiffProductBand(productDefaultGeoCoding, defaultProductSize, tiffImageRelativeFilePath, 0,
                                                                     metadata, filePathsHelper, maskBandNamesCallback, isMultiSize, null);
        if (geoTiffBandResult != null) {
            Band geoTiffBand = geoTiffBandResult.getBand();// readGeoTiffProductBand(tiffImageRelativeFilePath, 0);
            geoTiffBand.setNoDataValueUsed(false);
            geoTiffBand.setDescription("Defective Pixel");
            product.addBand(geoTiffBand);

            // add masks
            ColorIterator.reset();
            MuscateMetadata.Geoposition geoposition = geoTiffBandResult.getGeoPosition();// metadata.getGeoposition(width, height);
            String[] orderedBandNames = metadata.getOrderedBandNames(geoposition.id);
            for (int i = 0; i < orderedBandNames.length; i++) {
                String maskName = computeDefectivePixelMaskName(orderedBandNames[i]);
                if (isMaskAccepted(maskName) && !product.getMaskGroup().contains(maskName)) {
                    Mask mask = buildMaskFromBand(geoTiffBand, maskName, "Defective pixel", String.format("bit_set(%s,%d)", geoTiffBand.getName(), i), ColorIterator.next());
                    product.addMask(mask);
                }
            }
        }
    }

    public static MuscateProductReader.BandNameCallback buildAOTMaskBandNamesCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_IA_" + geoPosition.id;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildWVCMaskNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_IA_" + geoPosition.id;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildEdgeMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_Mask_Edge_" + geoPosition.id;
            }
        };
    }

    public static String computeAOTMaskName(MuscateMetadata.Geoposition geoPosition) {
        return "AOT_Interpolation_Mask_" + geoPosition.id;
    }

    public static String computeWVCMaskName(MuscateMetadata.Geoposition geoPosition) {
        return "WVC_Interpolation_Mask_" + geoPosition.id;
    }

    public static String computeEdgeMaskName(MuscateMetadata.Geoposition geoPosition) {
        return "edge_mask_" + geoPosition.id;
    }

    public static MuscateProductReader.BandNameCallback buildSaturationMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_Mask_Saturation_" + geoPosition.id;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildDetectorFootprintMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                int detector = getDetectorFromFilename(tiffImageRelativeFilePath);
                return String.format("Aux_Mask_Detector_Footprint_%s_%02d", geoPosition.id, detector);
            }
        };
    }

    public static String computeSaturationMaskName(String bandId) {
        return "saturation_" + bandId;
    }

    public static String computeDetectorFootprintMaskName(String tiffImageRelativeFilePath, String orderedBandName) {
        int detector = getDetectorFromFilename(tiffImageRelativeFilePath);
        return String.format("detector_footprint-%s-%02d", formatBandNameTo3Characters(orderedBandName), detector);
    }

    public static MuscateProductReader.BandNameCallback buildCloudMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_Mask_Cloud_" + geoPosition.id;
            }
        };
    }

    public static String computeCloudMaskAllName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_all_" + geoPosition.id;
    }

    public static String computeCloudMaskAllCloudName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_all_cloud_" + geoPosition.id;
    }

    public static String computeCloudMaskReflectanceName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_refl_" + geoPosition.id;
    }

    public static String computeCloudMaskReflectanceVarianceName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_refl_var_" + geoPosition.id;
    }

    public static String computeCloudMaskExtensionName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_extension_" + geoPosition.id;
    }

    public static String computeCloudMaskInsideShadowName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_shadow_" + geoPosition.id;
    }

    public static String computeCloudMaskOutsideShadowName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_sahdvar_" + geoPosition.id;
    }

    public static String computeCloudMaskCirrusName(MuscateMetadata.Geoposition geoPosition) {
        return "cloud_mask_cirrus_" + geoPosition.id;
    }

    public static MuscateProductReader.BandNameCallback buildGeophysicsMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_Mask_MG2_" + geoPosition.id;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildDefectivePixelMaskBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "Aux_Mask_Defective_Pixel_" + geoPosition.id;
            }
        };
    }

    public static String computeDefectivePixelMaskName(String orderedBandName) {
        return String.format("defective_%s", orderedBandName);
    }

    private static List<Band> readAngleBands(Rectangle productBounds, Dimension defaultProductSize, MuscateMetadata metadata, ProductSubsetDef subsetDef) {
        List<Band> angleBands = new ArrayList<>();

        MuscateMetadata.AnglesGrid sunAnglesGrid = metadata.getSunAnglesGrid();
        Band band;
        // add Zenith
        if(subsetDef == null || subsetDef.isNodeAccepted("sun_zenith")) {
            band = readAngleBand(productBounds, defaultProductSize, "sun_zenith", "Sun zenith angles", sunAnglesGrid.getSize(),
                                      sunAnglesGrid.getZenith(), sunAnglesGrid.getResolution(), metadata, subsetDef);
            angleBands.add(band);
        }

        // add Azimuth
        if(subsetDef == null || subsetDef.isNodeAccepted("sun_azimuth")) {
            band = readAngleBand(productBounds, defaultProductSize, "sun_azimuth", "Sun azimuth angles", sunAnglesGrid.getSize(),
                                 sunAnglesGrid.getAzimuth(), sunAnglesGrid.getResolution(), metadata, subsetDef);
            angleBands.add(band);
        }

        // viewing angles
        for (String bandId : metadata.getBandNames()) {
            MuscateMetadata.AnglesGrid anglesGrid = metadata.getViewingAnglesGrid(bandId);
            // add Zenith
            String bandNameZenith = "view_zenith_" + anglesGrid.getBandId();
            if(subsetDef == null || subsetDef.isNodeAccepted(bandNameZenith)) {
                band = readAngleBand(productBounds, defaultProductSize, bandNameZenith, "Viewing zenith angles", anglesGrid.getSize(),
                                     anglesGrid.getZenith(), anglesGrid.getResolution(), metadata, subsetDef);
                angleBands.add(band);
            }

            // add Azimuth
            String bandNameAzimuth = "view_azimuth_" + anglesGrid.getBandId();
            if(subsetDef == null || subsetDef.isNodeAccepted(bandNameAzimuth)) {
                band = readAngleBand(productBounds, defaultProductSize, bandNameAzimuth, "Viewing azimuth angles", anglesGrid.getSize(),
                                     anglesGrid.getAzimuth(), anglesGrid.getResolution(), metadata, subsetDef);
                angleBands.add(band);
            }
        }

        // add mean angles
        MuscateMetadata.AnglesGrid meanViewingAnglesGrid = metadata.getMeanViewingAnglesGrid();
        if (meanViewingAnglesGrid != null) {
            // add Zenith
            if(subsetDef == null || subsetDef.isNodeAccepted("view_zenith_mean")) {
                band = readAngleBand(productBounds, defaultProductSize, "view_zenith_mean", "Mean viewing zenith angles", meanViewingAnglesGrid.getSize(),
                                     meanViewingAnglesGrid.getZenith(), meanViewingAnglesGrid.getResolution(), metadata, subsetDef);
                angleBands.add(band);
            }

            // add Azimuth
            if(subsetDef == null || subsetDef.isNodeAccepted("view_azimuth_mean")) {
                band = readAngleBand(productBounds, defaultProductSize, "view_azimuth_mean", "Mean viewing azimuth angles", meanViewingAnglesGrid.getSize(),
                                     meanViewingAnglesGrid.getAzimuth(), meanViewingAnglesGrid.getResolution(), metadata, subsetDef);
                angleBands.add(band);
            }
        }

        return angleBands;
    }

    private static Rectangle computeBandBoundsBasedOnPercent(Rectangle productBounds, int defaultProductWidth, int defaultProductHeight, int defaultBandWidth, int defaultBandHeight) {
        float productOffsetXPercent = productBounds.x / (float)defaultProductWidth;
        float productOffsetYPercent = productBounds.y / (float)defaultProductHeight;
        float productWidthPercent = productBounds.width / (float)defaultProductWidth;
        float productHeightPercent = productBounds.height / (float)defaultProductHeight;
        int bandOffsetX = (int)(productOffsetXPercent * defaultBandWidth);
        int bandOffsetY = (int)(productOffsetYPercent * defaultBandHeight);
        int bandWidth = (int)Math.ceil(productWidthPercent * defaultBandWidth);
        int bandHeight = (int)Math.ceil(productHeightPercent * defaultBandHeight);
        return new Rectangle(bandOffsetX, bandOffsetY, bandWidth, bandHeight);
    }

    private static Band readAngleBand(Rectangle productBounds, Dimension defaultProductSize, String angleBandName, String description,
                                      Dimension defaultSize, float[] data, Point.Float resolution, MuscateMetadata metadata, ProductSubsetDef subsetDef) {

        Rectangle bandBounds;
        if (subsetDef == null || subsetDef.getSubsetRegion() == null) {
            bandBounds = new Rectangle(defaultSize.width, defaultSize.height);
        } else {
            bandBounds = computeBandBoundsBasedOnPercent(productBounds, defaultProductSize.width, defaultProductSize.height, defaultSize.width, defaultSize.height);
        }
        if (bandBounds.isEmpty()) {
            throw new IllegalStateException("The region of the angle band '"+angleBandName+"' is empty: x="+bandBounds.x+", y="+bandBounds.y+", width="+bandBounds.width+", height="+bandBounds.height+".");
        }

        int[] bandOffsets = {0};
        SampleModel sampleModel = new PixelInterleavedSampleModel(TYPE_FLOAT, bandBounds.width, bandBounds.height, 1, bandBounds.width, bandOffsets);
        DataBuffer buffer = new DataBufferFloat(sampleModel.getWidth() * sampleModel.getHeight());
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, null);

        for (int rowIndex = bandBounds.y; rowIndex<(bandBounds.y + bandBounds.height); rowIndex++) {
            int rowOffset = rowIndex * defaultSize.width;
            for (int columnIndex = bandBounds.x; columnIndex<(bandBounds.x + bandBounds.width); columnIndex++) {
                int index = rowOffset + columnIndex;
                raster.setSample(columnIndex - bandBounds.x, rowIndex - bandBounds.y, 0, data[index]);
            }
        }

        ColorSpace colorSpace = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        ColorModel colorModel = new ComponentColorModel(colorSpace, false, false, Transparency.TRANSLUCENT, TYPE_FLOAT);

        // and finally create an image with this raster
        BufferedImage image = new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
        PlanarImage sourceBandImage = PlanarImage.wrapRenderedImage(image);

        Band band = new Band(angleBandName, ProductData.TYPE_FLOAT32, sourceBandImage.getWidth(), sourceBandImage.getHeight());
        band.setDescription(description);
        band.setUnit("°");
        band.setNoDataValue(Double.NaN);
        band.setNoDataValueUsed(true);

        try {
            CoordinateReferenceSystem mapCRS = CRS.decode("EPSG:" + metadata.getEPSG());
            MuscateMetadata.Geoposition firstGeoPosition = metadata.getGeoPositions().get(0);
            CrsGeoCoding crsGeoCoding = new CrsGeoCoding(mapCRS, band.getRasterWidth(), band.getRasterHeight(), firstGeoPosition.ulx, firstGeoPosition.uly, resolution.x, resolution.y, 0.0, 0.0);
            band.setGeoCoding(crsGeoCoding);
        } catch (Exception e) {
            logger.warning(String.format("Unable to set geocoding to the band %s", angleBandName));
        }
        band.setImageToModelTransform(Product.findImageToModelTransform(band.getGeoCoding()));
        // set source image must be done after setGeocoding and setImageToModelTransform
        band.setSourceImage(sourceBandImage);
        return band;
    }

    public static int getDetectorFromFilename(String pathString) {
        Pattern p = Pattern.compile(".*D[0-9]{2}\\.tif");
        Matcher m = p.matcher(pathString);
        if (!m.matches()) {
            return 0;
        }
        return Integer.parseInt(pathString.substring(pathString.length() - 6, pathString.length() - 4));
    }

    private static String formatBandNameTo3Characters(String band) {
        if (band.startsWith("B") && band.length() == 2) {
            return String.format("B0%c", band.charAt(1));
        }
        return band;
    }

    public static MuscateMetadata readMetadata(VirtualDirEx productDirectory, String[] filePaths)
            throws IOException, InstantiationException, ParserConfigurationException, SAXException {

        String metadataFile = null;
        for (String file : filePaths) {
            if (file.endsWith(".xml") && file.matches(MuscateConstants.XML_PATTERN)) {
                metadataFile = file;
                break;
            }
        }
        if (metadataFile == null) {
            throw new NullPointerException("The metadata file is null.");
        }
        try (FilePathInputStream metadataInputStream = productDirectory.getInputStream(metadataFile)) {
            return (MuscateMetadata) XmlMetadataParserFactory.getParser(MuscateMetadata.class).parse(metadataInputStream);
        }
    }

    public static String getBandFromFileName(String filename) {
        Pattern pattern = Pattern.compile(MuscateConstants.REFLECTANCE_PATTERN);
        Matcher matcher = pattern.matcher(filename);
        if (matcher.matches()) {
            return matcher.group(8);
        }
        return "UNKNOWN";
    }

    private static String buildGroupPattern() {
        return "Aux_Mask:AOT_Interpolation:AOT:Surface_Reflectance:Flat_Reflectance:WVC:cloud:MG2:mg2:sun:view:edge:" +
                "detector_footprint-B01:detector_footprint-B02:detector_footprint-B03:detector_footprint-B04:detector_footprint-B05:detector_footprint-B06:detector_footprint-B07:detector_footprint-B08:" +
                "detector_footprint-B8A:detector_footprint-B09:detector_footprint-B10:detector_footprint-B11:detector_footprint-B12:defective:saturation";
    }

    public static String computeGeographicMaskName(MuscateConstants.GEOPHYSICAL_BIT geophysicalBit, MuscateMetadata.Geoposition geoposition) {
        return geophysicalBit.getPrefixName() + geoposition.id;
    }

    private static Mask buildGeophysicsMask(MuscateConstants.GEOPHYSICAL_BIT geophysicalBit, Band sourceBand, String maskName) {
        return buildMaskFromBand(sourceBand, maskName, geophysicalBit.getDescription(), String.format("bit_set(%s,%d)", sourceBand.getName(), geophysicalBit.getBit()), geophysicalBit.getColor());
    }

    private static Mask buildMaskFromBand(Band sourceBand, String maskName, String maskDescription, String maskExpression, Color maskColor) {
        Mask mask = Mask.BandMathsType.create(maskName, maskDescription, sourceBand.getRasterWidth(), sourceBand.getRasterHeight(), maskExpression, maskColor, 0.5);
        ProductUtils.copyGeoCoding(sourceBand, mask);
        return mask;
    }

    public static MuscateProductReader.BandNameCallback buildWVCImageBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "WVC_" + geoPosition.id;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildFlatReflectanceImageBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                String bandId = MuscateProductReader.getBandFromFileName(tiffImageRelativeFilePath);
                return "Flat_Reflectance_" + bandId;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildSurfaceReflectanceImageBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                String bandId = MuscateProductReader.getBandFromFileName(tiffImageRelativeFilePath);
                return "Surface_Reflectance_" + bandId;
            }
        };
    }

    public static MuscateProductReader.BandNameCallback buildAOTImageBandNameCallback() {
        return new MuscateProductReader.BandNameCallback() {
            @Override
            public String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath) {
                return "AOT_" + geoPosition.id;
            }
        };
    }

    public static interface BandNameCallback {
        String buildBandName(MuscateMetadata.Geoposition geoPosition, String tiffImageRelativeFilePath);
    }

    private static class GeoTiffBandResult {
        private final Band band;
        private final MuscateMetadata.Geoposition geoPosition;

        private GeoTiffBandResult(Band band, MuscateMetadata.Geoposition geoPosition) {
            this.band = band;
            this.geoPosition = geoPosition;
        }

        public Band getBand() {
            return band;
        }

        public MuscateMetadata.Geoposition getGeoPosition() {
            return geoPosition;
        }
    }

    private boolean isMultiSize(ProductFilePathsHelper filePathsHelper) throws IOException, IllegalAccessException, InvocationTargetException, InstantiationException {
        int defaultWidth = 0;
        int defaultHeight = 0;
        boolean isMultiSize = false;
        for (MuscateImage muscateImage : metadata.getImages()) {
            GeoTiffImageReader imageReader = null;
            if (muscateImage != null || muscateImage.nature != null) {
                for (String tiffImageRelativeFilePath : muscateImage.getImageFiles()) {
                    String tiffImageFilePath = filePathsHelper.computeImageRelativeFilePath(this.productDirectory, tiffImageRelativeFilePath);
                    try {
                        imageReader = GeoTiffImageReader.buildGeoTiffImageReader(this.productDirectory.getBaseFile().toPath(), tiffImageFilePath);
                        if (defaultWidth == 0) {
                            defaultWidth = imageReader.getImageWidth();
                        } else if (defaultWidth != imageReader.getImageWidth()) {
                            isMultiSize = true;
                            break;
                        }
                        if (defaultHeight == 0) {
                            defaultHeight = imageReader.getImageHeight();
                        } else if (defaultHeight != imageReader.getImageHeight()) {
                            isMultiSize = true;
                            break;
                        }
                    } finally {
                        if (imageReader != null) {
                            imageReader.close();
                        }
                    }
                }
            }
            if(isMultiSize){
                break;
            }
        }
        return isMultiSize;
    }
}
