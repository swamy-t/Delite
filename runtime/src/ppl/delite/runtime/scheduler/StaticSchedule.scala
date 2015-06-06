package ppl.delite.runtime.scheduler

import ppl.delite.runtime.executor.DeliteExecutable
import ppl.delite.runtime.graph.ops.DeliteOP
import java.util.LinkedList
import collection.mutable.ArrayBuffer

/**
 * Author: Kevin J. Brown
 * Date: Oct 11, 2010
 * Time: 4:34:43 PM
 * 
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

/**
 * This class represents all the results generated by the walktime scheduler
 */
object StaticSchedule {
  def apply(numResources: Int) = {
    val resources = new Array[ArrayBuffer[DeliteExecutable]](numResources)
    for (i <- 0 until numResources)
      resources(i) = new ArrayBuffer[DeliteExecutable]()
    new StaticSchedule(resources)
  }
}

class StaticSchedule(val resources: Array[ArrayBuffer[DeliteExecutable]]) {

  def apply(idx: Int) = resources(idx)

  def slice(indices: Seq[Int]) = {
    val r = for (i <- indices if i < resources.length) yield resources(i)
    new StaticSchedule(r.toArray)
  }

  /**
   * Currently this class only holds a single scheduling object
   * The outer array represents the resources scheduled across
   * The inner OpList is the schedule for that resource
   * Designed to grow
   */
}

object PartialSchedule {
  def apply(numResources: Int) = {
    val r = new Array[OpList](numResources)
    for (i <- 0 until numResources) r(i) = new OpList(r, i)
    new PartialSchedule(r)
  }

  def apply(resources: Array[OpList]) {
    new PartialSchedule(resources)
  }
}

class OpList(val siblings: Array[OpList] = null, val resourceID: Int = -1) {
  private val list = new LinkedList[DeliteOP]

  def apply(idx: Int) = list.get(idx)
  def +=(elem: DeliteOP) = add(elem)
  def add(elem: DeliteOP) = list.add(elem)
  def insert(idx: Int, elem: DeliteOP) = list.add(idx, elem)
  def remove = list.remove()
  def peek = list.peek()
  def peekLast = list.peekLast()
  def isEmpty = list.isEmpty
  def indexOf(elem: DeliteOP) = list.indexOf(elem)
  def size = list.size
  def contains(elem: DeliteOP) = list.contains(elem)

  def toArray(size: Int): Array[DeliteOP] = list.toArray(new Array[DeliteOP](size))
  def toArray(): Array[DeliteOP] = this.toArray(0)

  def foreach[U](f: DeliteOP => U) {
    val iter = list.iterator
    while (iter.hasNext) {
      f(iter.next)
    }
  }

  def availableAt(op:DeliteOP, sym: String, at:DeliteOP): Boolean = {
    if (list.contains(op) && (indexOf(op) < indexOf(at))) true // op is generated by this resource
    else if (op.getConsumers.filter(c => c.getInputs.exists(_._2==sym) && list.contains(c) && (indexOf(c) < indexOf(at))).nonEmpty) true // (op,sym) is already used by former kernels
    else false
  }

}

class PartialSchedule(resources: Array[OpList]) {

  val numResources = resources.length

  def apply(idx: Int) = resources(idx)

  def slice(start: Int, end: Int) = new PartialSchedule(resources.slice(start,end))

  def foreach[U](f: OpList => U) = resources.foreach(f)

  def map[B](f: OpList => B) = resources.map(f)

  def withFilter(p: OpList => Boolean) = resources.withFilter(p)

  def insertRelative(op: DeliteOP, existing: DeliteOP, offset: Int) {
    for (resource <- resources) {
      val idx = resource.indexOf(existing)
      if (idx != -1) {
        resource.insert(idx + offset, op)
        return
      }
    }
    sys.error("Could not find op: " + existing + " in schedule")
  }

  def insertBefore(op: DeliteOP, before: DeliteOP) = insertRelative(op, before, 0)

  def insertAfter(op: DeliteOP, after: DeliteOP) = insertRelative(op, after, 1)

  /**
   * Currently this class only holds a single scheduling object
   * The outer array represents the resources scheduled across
   * The inner OpList is the partial schedule of ops for that resource
   * Designed to grow
   */
}
