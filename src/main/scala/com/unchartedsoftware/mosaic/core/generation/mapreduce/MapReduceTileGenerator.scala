package com.unchartedsoftware.mosaic.core.generation.mapreduce

import com.unchartedsoftware.mosaic.core.analytic.Aggregator
import com.unchartedsoftware.mosaic.core.util.ValueExtractor
import com.unchartedsoftware.mosaic.core.projection.Projection
import com.unchartedsoftware.mosaic.core.generation.output.TileData
import com.unchartedsoftware.mosaic.core.generation.TileGenerator
import com.unchartedsoftware.mosaic.core.generation.request.TileRequest
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.Row
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import scala.util.Try

/**
 * Tile Generator which utilizes a map/reduce approach to
 * generating large tile sets.
 * @param sc a SparkContext
 * @param cExtractor a mechanism for grabbing the data-space coordinates from a source record
 * @param projection the projection from data to some space (i.e. 2D or 1D)
 * @param vExtractor a mechanism for grabbing the "value" column from a source record
 * @param binAggregator the desired bin analytic strategy
 * @param tileAggregator the desired tile analytic strategy
 * @tparam TC the abstract type representing a tile coordinate. Must feature a zero-arg constructor.
 * @tparam BC the abstract type representing a bin coordinate. Must feature a zero-arg
 *            constructor and should be something that can be represented in 1 dimension.
 * @tparam DC the abstract type representing a data-space coordinate
 * @tparam T Input data type for bin aggregators
 * @tparam U Intermediate data type for bin aggregators
 * @tparam V Output data type for bin aggregators, and input for tile aggregator
 * @tparam W Intermediate data type for tile aggregators
 * @tparam X Output data type for tile aggregators
 */
class MapReduceTileGenerator[DC, TC: ClassTag, BC, T, U: ClassTag, V, W, X](
  sc: SparkContext,
  cExtractor: ValueExtractor[DC],
  projection: Projection[DC, TC, BC],
  vExtractor: ValueExtractor[T],
  binAggregator: Aggregator[T, U, V],
  tileAggregator: Aggregator[V, W, X])(implicit tileCoordManifest: Manifest[TC])
extends TileGenerator[DC, TC, BC, T, U, V, W, X](sc, cExtractor, projection, vExtractor, binAggregator, tileAggregator) {

  /**
   * Converts raw input data into an RDD which contains only what we need:
   * rows which contain a tile coordinate, a bin and a value.
   */
  private def transformData(
    data: RDD[Row],
    tileSize: BC,
    bCExtractor: Broadcast[ValueExtractor[DC]],
    bProjection: Broadcast[Projection[DC, TC, BC]],
    bVExtractor: Broadcast[ValueExtractor[T]],
    bRequest: Broadcast[TileRequest[TC]]): RDD[(TC, (Int, Option[T]))] = {
    data.flatMap(r => {

      val request = bRequest.value
      //extract data-space coordinates for this row
      val dCoords = bCExtractor.value.rowToValue(r)
      //extract value for this row
      val value = bVExtractor.value.rowToValue(r)

      //map all input data to an RDD of (TC, (1Dbin, Option[value]))
      request.levels
      .flatMap(l => {
        val projection = bProjection.value
        val coords = projection.project(dCoords, l, tileSize)
        if (coords.isDefined && request.inRequest(coords.get._1)) {
          Seq((coords.get._1, (projection.binTo1D(coords.get._2, tileSize), value)))
        } else {
          Seq()
        }
      })
    })
  }

  def generate(data: RDD[Row], tileSize: BC, request: TileRequest[TC]): RDD[TileData[TC, V, X]] = {
    data.cache //ensure data is cached

    //broadcast stuff we'll use on the workers throughout our tilegen process
    val bProjection = sc.broadcast(projection)
    val bCExtractor = sc.broadcast(cExtractor)
    val bVExtractor = sc.broadcast(vExtractor)
    val bBinAggregator = sc.broadcast(binAggregator)
    val bTileAggregator = sc.broadcast(tileAggregator)
    val bRequest = sc.broadcast(request)

    //Start by transforming the raw input data into a distributed RDD(TC, (bin, Option[value]))
    val transformedData = transformData(data, tileSize, bCExtractor, bProjection, bVExtractor, bRequest)

    //Now we are going to use combineByKey, but we need some lambdas which are
    //defined in MapReduceTileGeneratorCombiner first.
    val combiner = new MapReduceTileGeneratorCombiner[DC, TC, BC, T, U, V](tileSize, bProjection, bVExtractor, bBinAggregator)

    //do the work in a closure which is sanitized of all non-serializable things
    val result = _sanitizedClosureGenerate(
                    transformedData,
                    combiner,
                    bProjection,
                    bVExtractor,
                    bBinAggregator,
                    bTileAggregator)

    bRequest.unpersist
    bCExtractor.unpersist
    bProjection.unpersist
    bVExtractor.unpersist
    bBinAggregator.unpersist
    bTileAggregator.unpersist

    result
  }

  def _sanitizedClosureGenerate(
    transformedData: RDD[(TC, (Int, Option[T]))],
    combiner: MapReduceTileGeneratorCombiner[DC, TC, BC, T, U, V],
    bProjection: Broadcast[Projection[DC, TC, BC]],
    bVExtractor: Broadcast[ValueExtractor[T]],
    bBinAggregator: Broadcast[Aggregator[T, U, V]],
    bTileAggregator: Broadcast[Aggregator[V, W, X]]
  ): RDD[TileData[TC, V, X]] = {
    //Do the actual combineByKey to produce finished bin data
    val tileData = transformedData.combineByKey(
      combiner.createCombiner,
      combiner.mergeValue,
      combiner.mergeCombiners
    )

    tileData.map(t => {
      val binAggregator = bBinAggregator.value
      val tileAggregator = bTileAggregator.value
      val projection = bProjection.value
      var tile: W = tileAggregator.default
      val key = t._1
      var binsTouched = 0

      val finishedBins = t._2.map(a => {
        if (!a.equals(binAggregator.default)) binsTouched+=1
        val bin = binAggregator.finish(a)
        tile = tileAggregator.add(tile, Some(bin))
        bin
      })
      new TileData[TC, V, X](key, finishedBins, binsTouched, binAggregator.finish(binAggregator.default), tileAggregator.finish(tile), projection)
    })
  }
}

private class MapReduceTileGeneratorCombiner[DC, TC, BC, T, U: ClassTag, V](
  tileSize: BC,
  bProjection: Broadcast[Projection[DC, TC, BC]],
  bVExtractor: Broadcast[ValueExtractor[T]],
  bBinAggregator: Broadcast[Aggregator[T, U, V]]) extends Serializable {

  val maxBins = bProjection.value.binTo1D(tileSize, tileSize)

  //will store intermediate values for the bin analytic
  private def makeBins [A:ClassTag] (length: Int, default: A): Array[A] = {
    Array.fill[A](length)(default)
  }

  //create a new combiner, with a fresh set of bins
  def createCombiner(firstValue: (Int, Option[T])): Array[U] = {
    val binAggregator = bBinAggregator.value
    val bins: Array[U] = makeBins(maxBins, binAggregator.default)
    bins(firstValue._1) = binAggregator.add(binAggregator.default, firstValue._2)
    bins
  }

  //how to add a value to a combiner
  def mergeValue(combiner: Array[U], newValue: (Int, Option[T])): Array[U] = {
    val binAggregator = bBinAggregator.value
    combiner(newValue._1) = binAggregator.add(combiner(newValue._1), newValue._2)
    combiner
  }

  //how to merge combiners
  def mergeCombiners(r1: Array[U], r2: Array[U]): Array[U] = {
    val binAggregator = bBinAggregator.value
    for (i <- 0 until maxBins) {
      r1(i) = binAggregator.merge(r1(i), r2(i))
    }
    r1
  }
}
