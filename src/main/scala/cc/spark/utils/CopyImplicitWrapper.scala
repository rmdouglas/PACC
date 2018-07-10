package cc.spark.utils

object CopyImplicitWrapper {

  //The first bit is 1, the node is high node.
  //The second bit is 1, the node is a copy.
  //The next 10 bits are the copyid.
  //Remainder are the nodeid.

  val COPY_MASK = 0x40000000000L
  val HIGH_MASK = 0x80000000000L
  val LOW_MASK = ~HIGH_MASK
  val COPYID_MASK = 0x3FF00000000L

  implicit class CopyOps(n: Long){

    def copyId: Int = ((n << 2) >>> 54).toInt

    def isHigh: Boolean = (n & HIGH_MASK) != 0

    def isCopy: Boolean = (n & COPY_MASK) != 0

    def nonCopy: Boolean = (n & COPY_MASK) == 0

    def nodeId: Long = 0xFFFFFFFFL & n

    def copy(v: Long, p: Int) = COPY_MASK | ((v.nodeId % p) << 32) | n.nodeId

    def high: Long = n | HIGH_MASK

    def low: Long = n & LOW_MASK

    def toTuple: String = n.nodeId + (if(n.isHigh) "h" else "") + (if(n.isCopy) "c" + n.copyId else "" )
  }
}