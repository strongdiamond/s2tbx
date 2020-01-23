package org.esa.s2tbx.dataio.gdal;

import org.esa.s2tbx.dataio.gdal.drivers.GDAL;
import org.esa.s2tbx.dataio.gdal.drivers.GDALConstConstants;
import org.esa.snap.core.datamodel.ProductData;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GDAL Loader class for loading GDAL native libraries from distribution.
 *
 * @author Jean Coravu
 * @author Adrian Drăghici
 */
public final class GDALLoader {

    private static final GDALLoader INSTANCE = new GDALLoader();
    private static final Logger logger = Logger.getLogger(GDALLoader.class.getName());

    private boolean ready = false;
    private GDALVersion gdalVersion;
    private URLClassLoader gdalVersionLoader;

    private Map<Integer, Integer> bandToGDALDataTypes;

    private GDALLoader() {

    }

    /**
     * Returns instance of this class.
     *
     * @return the instance of this class.
     */
    public static GDALLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes GDAL native libraries to be used by SNAP.
     *
     * @return the absolute path of the GDAL distribution
     * @throws IOException When IO error occurs
     */
    public Path initGDAL() throws IOException {
        if (!this.ready) {
            this.gdalVersion = GDALVersion.getGDALVersion();
            GDALDistributionInstaller.setupDistribution(this.gdalVersion);
            this.gdalVersionLoader = new URLClassLoader(new URL[]{this.gdalVersion.getJNILibraryFilePath().toUri().toURL()}, GDALLoader.class.getClassLoader());
            this.ready = true;
            initDrivers();
            postGDALInit();
        }
        return Paths.get(this.gdalVersion.getLocation());
    }

    /**
     * Saves internal mappings between GDAL data types and bands data types.
     */
    private void postGDALInit() {
        this.bandToGDALDataTypes = new HashMap<>();
        this.bandToGDALDataTypes.put(ProductData.TYPE_UINT8, GDALConstConstants.gdtByte());
        this.bandToGDALDataTypes.put(ProductData.TYPE_UINT16, GDALConstConstants.gdtInt16());
        this.bandToGDALDataTypes.put(ProductData.TYPE_INT32, GDALConstConstants.gdtInt32());
        this.bandToGDALDataTypes.put(ProductData.TYPE_UINT32, GDALConstConstants.gdtUint32());
        this.bandToGDALDataTypes.put(ProductData.TYPE_FLOAT32, GDALConstConstants.gdtFloat32());
        this.bandToGDALDataTypes.put(ProductData.TYPE_FLOAT64, GDALConstConstants.gdtFloat64());
    }

    /**
     * Gets the GDAL JNI URL class loader for loading JNI drivers of current version native libraries.
     *
     * @return the GDAL JNI URL class loader for loading JNI drivers of current version native libraries
     */
    public URLClassLoader getGDALVersionLoader() {
        if (!this.ready) {
            throw new IllegalStateException("GDAL Loader not ready.");
        }
        return this.gdalVersionLoader;
    }

    /**
     * Gets the GDAL data type corresponding to the data type of a band.
     *
     * @param bandDataType the data type of the band to convert to the GDAL data type
     * @return the GDAL data type
     */
    public int getGDALDataType(int bandDataType) {
        if (!this.ready) {
            throw new IllegalStateException("GDAL library not initialized");
        }
        Integer gdalResult = this.bandToGDALDataTypes.get(bandDataType);
        if (gdalResult != null) {
            return gdalResult;
        }
        throw new IllegalArgumentException("Unknown band data type " + bandDataType + ".");
    }

    /**
     * Get the data type of the band corresponding to the GDAL data type.
     *
     * @param gdalDataType the GDAL data type to convert to the data type of the band
     * @return the data type of the band
     */
    public int getBandDataType(int gdalDataType) {
        if (!this.ready) {
            throw new IllegalStateException("GDAL library not initialized");
        }
        for (Map.Entry<Integer, Integer> entry : this.bandToGDALDataTypes.entrySet()) {
            if (entry.getValue() == gdalDataType) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("Unknown band data type " + gdalDataType + ".");
    }

    /**
     * Init the drivers if the GDAL library is installed.
     */
    private void initDrivers() {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Init the GDAL drivers on " + this.gdalVersion.getOsCategory().getOperatingSystemName() + ".");
        }
        GDAL.allRegister();// GDAL init drivers
    }
}