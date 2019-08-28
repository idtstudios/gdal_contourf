package com.github.brewin.gdal_contourf

import com.github.brewin.gdal_contourf.algorithm.MarchingSquares
import com.github.brewin.gdal_contourf.algorithm.Polygon
import kotlinx.coroutines.runBlocking
import org.gdal.gdal.TranslateOptions
import org.gdal.gdal.gdal
import org.gdal.ogr.Feature
import org.gdal.ogr.FieldDefn
import org.gdal.ogr.Geometry
import org.gdal.ogr.ogr
import org.gdal.ogr.ogrConstants.*
import org.gdal.osr.SpatialReference
import java.util.*
import kotlin.system.exitProcess

object GdalContourF {

    private fun polygonsToOgrMultiPolygon(polygons: List<Polygon>): Geometry {
        val multiPolygon = Geometry(wkbMultiPolygon)
        for ((exterior, interiors) in polygons) {
            val exteriorRing = Geometry(wkbLinearRing)
            val polygon = Geometry(wkbPolygon)
            for (point in exterior.points) {
                exteriorRing.AddPoint_2D(point.x, point.y)
            }
            polygon.AddGeometry(exteriorRing)
            for (interior in interiors) {
                val interiorRing = Geometry(wkbLinearRing)
                for (point in interior.points) {
                    interiorRing.AddPoint_2D(point.x, point.y)
                }
                polygon.AddGeometry(interiorRing)
            }
            if (!polygon.IsEmpty()) {
                multiPolygon.AddGeometry(polygon)
            }
        }
        return multiPolygon
    }

    private suspend fun process(
        grid: Array<DoubleArray>,
        levels: List<Double>,
        geoTransform: DoubleArray,
        outSrs: SpatialReference,
        outputFormat: String,
        outputOptions: List<String>,
        outFilePath: String
    ) {
        val layerName = "levels"
        val featureName = "level"

        val outDataSource = ogr.GetDriverByName(outputFormat)
            .CreateDataSource(outFilePath, Vector(outputOptions))
        val layer = outDataSource.CreateLayer(layerName, outSrs, wkbMultiPolygon)
            .apply { CreateField(FieldDefn(featureName, OFTReal)) }

        MarchingSquares(grid, geoTransform)
            .contour(levels.toDoubleArray())
            .forEachIndexed { i, levelPolygons ->
                println("OGR-ifying level ${levels[i]}...")
                val levelMultiPolygon = polygonsToOgrMultiPolygon(levelPolygons)
                if (!levelMultiPolygon.IsEmpty()) {
                    val feature = Feature(layer.GetLayerDefn())
                        .apply {
                            SetGeometry(levelMultiPolygon)
                            SetField(featureName, levels[i])
                        }
                    layer.CreateFeature(feature)
                    feature.delete()
                }
            }

        // Must explicitly destroy output data source to write the whole file.
        println("Writing vector file...")
        outDataSource.delete()
    }

    suspend fun rasterToVector(
        inputRasterPath: String,
        gzipped: Boolean,
        band: Int,
        levels: List<Double>,
        simplification: Int,
        outputEpsgId: Int,
        outputFormat: String,
        outputOptions: List<String>,
        outputPath: String
    ) {
        require(simplification in 0..90) { "Error: simplification must be between 0 and 90" }

        gdal.AllRegister()
        gdal.UseExceptions()
        ogr.RegisterAll()
        ogr.UseExceptions()

        val outSrs = SpatialReference()
            .apply { ImportFromEPSG(outputEpsgId) }
        val translateArgs = "-of VRT -r cubicspline -outsize ${100 - simplification}% 0"
        val reprojectedDataset = gdal.Translate(
            "/vsimem/reprojected.vrt",
            GdalUtil.reproject(GdalUtil.openRaster(inputRasterPath, gzipped), outSrs),
            TranslateOptions(gdal.ParseCommandLine(translateArgs))
        )
        val grid = GdalUtil.bandTo2dDoubleArray(reprojectedDataset.GetRasterBand(band))
        val geoTransform = reprojectedDataset.GetGeoTransform()

        process(grid, levels, geoTransform, outSrs, outputFormat, outputOptions, outputPath)
    }

    private fun mapArgs(args: Array<String>): Map<String, List<String>> =
        args.fold(Pair(emptyMap<String, List<String>>(), "")) { (map, lastKey), elem ->
            if (elem.startsWith("-")) Pair(map + (elem to emptyList()), elem)
            else Pair(map + (lastKey to map.getOrDefault(lastKey, emptyList()) + elem), lastKey)
        }.first

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage:")
            println("java -jar gdal_contourf-v1.1-all.jar --in input.tif --band 1 \\")
            println("   --levels 0 50 100 --simplification 30 --epsg 4326 \\")
            println("   --format MBTile --options MAXZOOM=7 --out output.mbtiles")
            exitProcess(1)
        }

        val a = mapArgs(args)

        val raster = a["--in"]?.singleOrNull()
            ?: throw IllegalArgumentException("--in is required (--in raster.tif)")
        val gzipped = a["--gzipped"]?.singleOrNull()?.toBoolean() ?: false
        val band = a["--band"]?.singleOrNull()?.toIntOrNull() ?: 1
        val levels = a["--levels"]?.map(String::toDouble)
            ?: throw IllegalArgumentException("--levels is required (--levels 0 50 100)")
        val simp = a["--simplification"]?.singleOrNull()?.toInt() ?: 0
        val epsg = a["--epsg"]?.singleOrNull()?.toIntOrNull() ?: 4326
        val format = a["--format"]?.singleOrNull()
            ?: throw IllegalArgumentException("--format is required (--format GeoJSON)")
        val options = a["--options"].orEmpty()
        val out = a["--out"]?.singleOrNull()
            ?: throw IllegalArgumentException("--out is required (--out out.geojson)")

        //arrayOf(raster, band, levels, simp, epsg, format, options, out).map(::println)

        runBlocking {
            rasterToVector(raster, gzipped, band, levels, simp, epsg, format, options, out)
        }
    }
}