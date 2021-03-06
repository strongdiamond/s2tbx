package org.esa.s2tbx.dataio.s2.tiles;

import org.esa.snap.jp2.reader.JP2ImageFile;
import org.esa.snap.core.image.MosaicMatrix;
import org.esa.snap.lib.openjpeg.jp2.TileLayout;

import java.awt.*;
import java.nio.file.Path;

/**
 * Created by jcoravu on 8/1/2020.
 */
public class S2MosaicBandMatrixCell implements BandMatrixCell {

    private final JP2ImageFile jp2ImageFile;
    private final Path cacheDir;
    private final TileLayout tileLayout;
    private final int cellWidth;
    private final int cellHeight;

    public S2MosaicBandMatrixCell(JP2ImageFile jp2ImageFile, Path cacheDir, TileLayout tileLayout, int cellWidth, int cellHeight) {
        this.jp2ImageFile = jp2ImageFile;
        this.cacheDir = cacheDir;
        this.tileLayout = tileLayout;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
    }

    @Override
    public int getCellWidth() {
        return this.cellWidth;
    }

    @Override
    public int getCellHeight() {
        return this.cellHeight;
    }

    @Override
    public int getDataBufferType() {
        return tileLayout.dataType;// DataBuffer.TYPE_USHORT;
    }

    public Dimension getDefaultImageSize() {
        return new Dimension(getCellWidth(), getCellHeight());
    }

    public Dimension getDecompressedTileSize() {
        return new Dimension(tileLayout.tileWidth, tileLayout.tileHeight);
    }

    public JP2ImageFile getJp2ImageFile() {
        return jp2ImageFile;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public int getBandIndex() {
        return 0;
    }

    public int getBandCount() {
        return tileLayout.numBands;
    }

    public int getResolutionCount() {
        return tileLayout.numResolutions;
    }
}
