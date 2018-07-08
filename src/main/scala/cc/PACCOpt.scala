package cc

import java.util.StringTokenizer

import cc.utils.{LongExternalSorter, SerializableConfiguration}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.Logger
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import utils.StarGroupOps._
import utils.FilteringOps._

object PACCOpt{

  private val logger = Logger.getLogger(getClass)

  case class Config(localThreshold: Int = 100000, numPartitions: Int = 80,
                    inputPath: String = "", outputPath: String = "")

  val APP_NAME: String = "pacc-opt"
  val VERSION: String = "0.1"

  def main(args: Array[String]): Unit = {

    val parser = new scopt.OptionParser[Config](APP_NAME) {
      head(APP_NAME, VERSION)

      opt[Int]('t', "localThreshold")
        .action((x, c) => c.copy(localThreshold = x))
        .text("if the number of remaining edges are lower than this value, " +
          "pacc run a single machine algorithm (LocalCC). (default: 100000)")

      opt[Int]('p', "numPartition").required()
        .action((x, c) => c.copy(numPartitions = x))
        .text("the number of partitions. (default: 80)")

      arg[String]("input")
        .action((x, c) => c.copy(inputPath = x))
        .text("input file path.")

      arg[String]("output")
        .action((x, c) => c.copy(outputPath = x))
        .text("output file path.")
    }

    parser.parse(args, Config()) match {
      case Some(opts) =>

        logger.info(f"inputPath: ${opts.inputPath}, output: ${opts.outputPath}, " +
          f"localTrheshold: ${opts.localThreshold}, numPartitions: ${opts.numPartitions}")

        val conf = new SparkConf().setAppName("[" + APP_NAME + "]" + opts.inputPath)
          .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .set("spark.kryoserializer.buffer", "24m")

        val sc = new SparkContext(conf)

        FileSystem.get(sc.hadoopConfiguration).delete(new Path(opts.outputPath), true)

        run(opts.inputPath, opts.numPartitions, opts.localThreshold, sc)
          .map { case (u, v) => u + "\t" + v }
          .saveAsTextFile(opts.outputPath)

        sc.stop()

      case None =>
    }
  }

  /**
    * submit the spark job.
    *
    * @param inputPath input file path.
    * @param numPartitions the number of partitions
    * @param localThreshold if the number of remaining edges are lower than this value,
    *                      pacc run a single machine algorithm (LocalCC).
    * @param sc spark context.
    * @return an RDD containing connected components
    */
  def run(inputPath: String, numPartitions: Int,
          localThreshold: Int, sc: SparkContext): RDD[(Long, Long)] = {

    val tmpPath = inputPath + ".pacc.tmp"

    val t0 = System.currentTimeMillis()

    //initialize
    var out = sc.textFile(inputPath).map{ line =>
      val st = new StringTokenizer(line)
      val u = st.nextToken().toLong
      val v = st.nextToken().toLong
      (u, v)
    }

    var numEdges = out.count()
    val t1 = System.currentTimeMillis()



    var converge = false

    var round = 0

    do{
      // partitioning step
      if(numEdges > localThreshold) {

        val t00 = System.currentTimeMillis()

        val (lout, l_change, lout_size, lcc_size, lin_size) = largeStar(out, numPartitions, round, tmpPath)
        val t01 = System.currentTimeMillis()

        val (sout, s_change, sout_size, sin_size) = smallStar(lout, numPartitions, round, tmpPath)
        val t02 = System.currentTimeMillis()


        val ltime = (t01-t00)/1000.0
        val stime = (t02-t01)/1000.0
        val ttime = (t02-t00)/1000.0

        out = sout

        logger.info(f"round($round) - lout: $lout_size, lcc: $lcc_size, lin: $lin_size, sout: $sout_size, sin: $sin_size, " +
          f"lchange: $l_change, schange: $s_change")

        println(s"star\t$round\t$lout_size\t$lcc_size\t$lin_size\t$sout_size\t$sin_size\t$l_change\t$s_change\t$ltime\t$stime\t$ttime")

        converge = l_change == 0 && s_change == 0
        numEdges = sout_size

        round += 1
      }
      else{
        //do LocalCC

        val t00 = System.currentTimeMillis()

        out = UnionFind.run(out.map{ case (u, v) => (if (u < 0) ~u else u, v) })
        converge = true
        val t01 = System.currentTimeMillis()

        val ltime = (t01-t00)/1000.0

        println(s"local\t$round\t$ltime")
      }
    }while(!converge)

    val t2 = System.currentTimeMillis()

    val others = sc.sequenceFile[Long, Long](tmpPath)

    // computation step
    val res = ccComputation(out ++ others, numPartitions)

    val t3 = System.currentTimeMillis()

    FileSystem.get(sc.hadoopConfiguration).deleteOnExit(new Path(tmpPath))

    val itime = (t1-t0)/1000.0
    val rtime = (t2-t1)/1000.0
    val ctime = (t3-t2)/1000.0
    val ttime = (t3-t0)/1000.0
    val inputFileName = inputPath.split("/").last

    println(s"$APP_NAME\t$inputFileName\t$localThreshold\t$numPartitions\t$round\t$itime\t$rtime\t$ctime\t$ttime")

    res
  }

  /**
    * CC-Computation operation.
    * This operation conducts LocalCC in each partition.
    *
    * @param remains input RDD
    * @param numPartitions the number of partitions
    * @return final output RDD containing connected components
    */
  def ccComputation(remains: RDD[(Long, Long)], numPartitions: Int): RDD[(Long, Long)] = {
    val res = remains.map{ case (u, v) => (if(u < 0) ~u else u, v) }
      .partitionBy(new HashPartitioner(numPartitions))
      .mapPartitions(edges => UnionFind.run(edges)).persist()

    res.count()

    res
  }


  /**
    * PA-Large-Star Operation.
    * For each node n, this operation links each large neighbor v to the minimum node mcu(p(v))
    * in the same partition p(v) that contains the neighbor v.
    *
    * @param inputRDD the input rdd
    * @param numPartitions the number of partitions
    * @param round current round number
    * @param tmpPath the temporary path to save the intermediate results
    * @return (RDD for next round input, # changed edges, # of 'out' edges,
    *         # filtered 'cc' edges, # filtered 'in' edges)
    */
  def largeStar(inputRDD: RDD[(Long, Long)], numPartitions: Int,
                round: Int, tmpPath: String): (RDD[(Long, Long)], Long, Long, Long, Long) = {

    val sc = inputRDD.sparkContext

    val NUM_CHANGES = sc.longAccumulator
    val LCC_SIZE = sc.longAccumulator
    val LIN_SIZE = sc.longAccumulator
    val LOUT_SIZE = sc.longAccumulator

    val groupedRDD = inputRDD.flatMap{
      case (u, v) =>
        if (u < 0) Seq((v, u))
        else Seq((u, v), (v, u))
    }.starGrouped()

    val tmpPaths = sc.hadoopConfiguration.getTrimmedStrings("yarn.nodemanager.local-dirs")

    val res_all = groupedRDD.mapPartitions{ it =>

      val longExternalSorter = new LongExternalSorter(tmpPaths)

      def processNode(x: (Long, Iterator[Long])): Iterator[(Boolean, Long, Long)] ={
        val (u, uN) = x

        val mcu = Array.fill[Long](numPartitions)(Long.MaxValue)
        mcu((u % numPartitions).toInt) = u

        var isStar = true

        val _uN_large = uN.filter { v_raw =>
          val v = if (v_raw >= 0) {
            isStar = false
            v_raw
          } else ~v_raw

          val vp = (v % numPartitions).toInt
          mcu(vp) = Math.min(v, mcu(vp))

          v > u
        }

        val uN_large = longExternalSorter.sort(_uN_large)

        val mu = mcu.min

        if (isStar) uN_large.map { v_raw => LCC_SIZE.add(1); (false, ~v_raw, u) }
        else{
          uN_large.map{ v_raw =>
            val vIsLeaf = v_raw < 0
            val v: Long = if(vIsLeaf) ~v_raw else v_raw

            val vp = (v % numPartitions).toInt
            val mcu_vp = mcu(vp)

            if(v != mcu_vp) {

              if(mcu_vp != u) NUM_CHANGES.add(1)

              if(vIsLeaf){
                LIN_SIZE.add(1)
                (false, v, mcu_vp)
              }
              else{
                LOUT_SIZE.add(1)
                (true, v, mcu_vp)
              }
            }
            else{// v is a local minimum but not the global minimum because 'it' has only large neighbors.
              LOUT_SIZE.add(1)

              if(mu != u) NUM_CHANGES.add(1)
              (true, v, mu)
            }
          }
        }
      }

      it.flatMap{processNode}


    }

    val hconf = new SerializableConfiguration(sc.hadoopConfiguration)
    val lout = res_all.filtered(tmpPath, f"large-$round%05d", hconf).persist(StorageLevel.DISK_ONLY)
    lout.count()

    inputRDD.unpersist(false)

    (lout, NUM_CHANGES.value, LOUT_SIZE.value, LCC_SIZE.value, LIN_SIZE.value)

  }

  /**
    * PA-Small-Star Operation.
    * For each node n, this operation links each small neighbor v to the minimum node mcu(p(v))
    * in the same partition p(v) that contains the neighbor v.
    *
    * @param inputRDD the input rdd
    * @param numPartitions the number of partitions
    * @param round current round number
    * @param tmpPath the temporary path to save the intermediate results
    * @return (RDD for next round input, # changed edges, # of 'out' edges,
    *         # filtered 'in' edges)
    */
  def smallStar(inputRDD: RDD[(Long, Long)], numPartitions: Int,
                round: Int, tmpPath: String): (RDD[(Long, Long)], Long, Long, Long) = {

    val sc = inputRDD.sparkContext

    val NUM_CHANGES = sc.longAccumulator
    val SIN_SIZE = sc.longAccumulator
    val SOUT_SIZE = sc.longAccumulator

    val groupedRDD = inputRDD.flatMap{
      case (u, v) => Seq((u, v), (v, u))
    }.starGrouped()

    val tmpPaths = sc.hadoopConfiguration.getTrimmedStrings("yarn.nodemanager.local-dirs")

    val res_all = groupedRDD.mapPartitions{ it =>

      val longExternalSorter = new LongExternalSorter(tmpPaths)

      def processNode(x: (Long, Iterator[Long])): Iterator[(Boolean, Long, Long)] ={
        val (u, uN) = x

        val mcu = Array.fill[Long](numPartitions)(Long.MaxValue)
        val up = (u % numPartitions).toInt
        mcu(up) = u

        var isLeaf = true

        val _uN_small = uN.filter { v =>

          if(v > u) isLeaf = false
          val vp = (v % numPartitions).toInt
          mcu(vp) = Math.min(v, mcu(vp))

          v < u
        }

        val uN_small = longExternalSorter.sort(_uN_small)

        val mu = mcu.min

        val sout = uN_small filter {_ != mu} map { v =>

          val vp = (v % numPartitions).toInt

          NUM_CHANGES.add(1)
          SOUT_SIZE.add(1)

          if (v != mcu(vp))
            (true, v, mcu(vp))
          else // v is mcu but not mu
            (true, v, mu)

        }

        if (u != mcu(up)) {
          if (isLeaf) {
            SIN_SIZE.add(1)
            sout ++ Iterator((false, u, mcu(up)))
          }
          else {
            SOUT_SIZE.add(1)
            sout ++ Iterator((true, u, mcu(up)))
          }
        }
        else if (u != mu) {
          SOUT_SIZE.add(1)
          sout ++ Iterator((true, if (isLeaf) ~u else u, mu))
        }
        else sout

      }

      it.flatMap{processNode}

    }

    val hconf = new SerializableConfiguration(sc.hadoopConfiguration)

    val sout = res_all.filtered(tmpPath, f"small-$round%05d", hconf).persist(StorageLevel.DISK_ONLY)

    sout.count()

    inputRDD.unpersist(false)

    (sout, NUM_CHANGES.value, SOUT_SIZE.value, SIN_SIZE.value)

  }

}