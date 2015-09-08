package com.unchartedsoftware.mosaic.core.analytic.numeric

import org.scalatest._
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import com.unchartedsoftware.mosaic.core.projection._
import com.unchartedsoftware.mosaic.core.generation.accumulator.AccumulatorTileGenerator
import com.unchartedsoftware.mosaic.core.generation.output._
import com.unchartedsoftware.mosaic.core.generation.request._
import com.unchartedsoftware.mosaic.core.analytic._
import com.unchartedsoftware.mosaic.core.analytic.numeric._
import com.unchartedsoftware.mosaic.core.util.DataFrameUtil
import org.apache.spark.sql.Row

object Spark {
  val conf = new SparkConf().setAppName("mosaic")
  val sc = new SparkContext(conf)
}

//define tests here so that scalatest stuff isn't serialized into spark closures
object AccumulatorTileGeneratorSpecClosure {
  case class Element(x: Double)

  val sqlContext = new org.apache.spark.sql.SQLContext(Spark.sc)
  import sqlContext.implicits._

  def testClosure(
    data: Array[Double],
    projection: Projection[(Int, Int)],
    request: TileSeqRequest[(Int, Int)],
    extractor: ValueExtractor[Double]
  ): Seq[TileData[(Int, Int), java.lang.Double, (java.lang.Double, java.lang.Double)]] = {
    //generate some random data
    var frame = Spark.sc.parallelize(data).map(i => Element(i)).toDF()

    //create generator
    val gen = new AccumulatorTileGenerator[(Int, Int), Double, Double, java.lang.Double, (Double, Double), (java.lang.Double, java.lang.Double)](Spark.sc, projection, extractor, CountAggregator, MaxMinAggregator)

    //kickoff generation
    gen.generate(frame, request)
  }
}

class AccumulatorTileGeneratorSpec extends FunSpec {
  describe("AccumulatorTileGenerator") {
    describe("#generate()") {
      it("should generate tile level 0, correctly distributing input points into bins") {

        //generate some random data
        val data = Array.fill(10)(0D).map(a => Math.random)

        //manually bin
        val manualBins = data.groupBy(a => a > 0.5).map(a => (a._1, a._2.length))

        //create projection, request, extractor
        val projection = new SeriesProjection(2, 0, 1, 0, 1D, 0D)
        val request = new TileSeqRequest[(Int, Int)](Seq((0,0)), projection)
        val extractor = new ValueExtractor[Double] {
          override def rowToValue(r: Row): Option[Double] = {
            return None
          }
        }

        val tiles = AccumulatorTileGeneratorSpecClosure.testClosure(data, projection, request, extractor)
        assert(tiles.length === 1) //did we generate a tile?

        //verify binning
        assert(tiles(0).bins(0) === manualBins.get(false).get)
        assert(tiles(0).bins(1) === manualBins.get(true).get)
      }

      it("should generate successive tile levels, correctly distributing input points into bins") {

        //generate some random data
        val data = Array.fill(10)(0D).map(a => Math.random)

        //create projection, request, extractor
        val projection = new SeriesProjection(2, 0, 1, 0, 1D, 0D)
        val request = new TileSeqRequest[(Int, Int)](Seq((0,0), (1,0), (1,1)), projection)
        val extractor = new ValueExtractor[Double] {
          override def rowToValue(r: Row): Option[Double] = {
            return None
          }
        }

        val tiles = AccumulatorTileGeneratorSpecClosure.testClosure(data, projection, request, extractor)
        assert(tiles.length === 3) //did we generate tiles?

        //map the result so that it's easier to work with
        val tilesMap = tiles.map(a => (a.coords, a)).toMap

        //verify binning of level 1 by aggregating it into level 0
        val combinedOneBins = tilesMap.get((1,0)).get.bins ++ tilesMap.get((1,1)).get.bins
        var j = 0
        for (i <- 0 until 10) {
          val zeroBin = tilesMap.get((0,0)).get.bins(i)
          assert(zeroBin === combinedOneBins(j) + combinedOneBins(j+1))
          j = j + 2
        }
      }
    }
  }
}
