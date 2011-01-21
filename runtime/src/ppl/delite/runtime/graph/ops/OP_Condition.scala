package ppl.delite.runtime.graph.ops

import ppl.delite.runtime.graph.DeliteTaskGraph
import ppl.delite.runtime.graph.targets.Targets

/**
 *
 */

class OP_Condition(val id: String, resultType: Map[Targets.Value, String],
                   val predicateGraph: DeliteTaskGraph, val predicateValue: String,
                   val thenGraph: DeliteTaskGraph, val thenValue: String,
                   val elseGraph: DeliteTaskGraph, val elseValue: String)
  extends OP_Control {

  def supportsTarget(target: Targets.Value) = resultType.contains(target)

  def outputType(target: Targets.Value) = resultType(target)
  override def outputType: String = resultType(Targets.Scala)

  def isReturner(idx: Int) = {
    if (thenGraph.result != null)
      (thenGraph.result.scheduledResource == idx)
    else if (elseGraph.result != null)
      (elseGraph.result.scheduledResource == idx)
    else true //should only be 1 in this case
  }

  /**
   * creates a Condition chunk for each requested resource and destroys the original
   */
  def makeChunks(indices: Seq[Int]) = {
    var returner: OP_Condition = null
    val chunks =
      for (idx <- indices) yield {
        val resultMap = if (isReturner(idx)) resultType else Targets.unitTypes
        val r = new OP_Condition(id+"_"+idx, resultMap, predicateGraph, predicateValue,
        thenGraph, thenValue, elseGraph, elseValue)
        r.dependencyList = dependencyList
        r.inputList = inputList
        r.consumerList = consumerList
        for (dep <- getDependencies) dep.addConsumer(r)
        for (c <- getConsumers) c.addDependency(r)
        if (isReturner(idx)) returner = r

        //add special consumer ops
        predicateGraph.schedule(idx).add(new GetterOp(id+"p_"+idx))
        if (resultMap(Targets.Scala) != "Unit") { //returns result and isReturner
          thenGraph.schedule(idx).add(new GetterOp(id+"t_"+idx))
          elseGraph.schedule(idx).add(new GetterOp(id+"e_"+idx))
        }

        r
      }

    this.replaceAll(returner)
    chunks
  }

  private class GetterOp(val id: String) extends DeliteOP {

    def supportsTarget(target: Targets.Value) = true
    def outputType(target: Targets.Value) = target match {
      case Targets.Scala => "Unit"
      case Targets.Cuda => "void"
    }

    def task = ""
    def isDataParallel = false
    def cost = 0
    def size = 0
  }

}
