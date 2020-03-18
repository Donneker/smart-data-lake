/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.workflow

import com.github.mdr.ascii.graph.Graph
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.workflow.DAGHelper.NodeId
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success, Try}

private[smartdatalake] object DAGHelper {
  type NodeId = String
}

private[smartdatalake] trait DAGNode {
  def nodeId: NodeId
}

private[smartdatalake] trait DAGEdge {
  def nodeIdFrom: NodeId
  def nodeIdTo: NodeId
  def resultId: String
}

private[smartdatalake] trait DAGResult {
  def resultId: String
}

private[smartdatalake] case class TaskCancelledException(id: NodeId) extends Exception
private[smartdatalake] case class TaskPredecessorFailureException(id: NodeId, ex: Throwable) extends Exception(ex)

/**
 * A generic directed acyclic graph (DAG) consisting of [[DAGNode]]s interconnected with directed [[DAGEdge]]s.
 *
 * This DAG can have multiple start nodes and multiple end nodes as well as disconnected parts.
 *
 * @param sortedNodes All nodes of the DAG sorted in topological order.
 * @param incomingEdgesMap A lookup table for incoming edges indexed by node id.
 * @param startNodes Starting points for DAG execution.
 * @param endNodes End points for DAG execution.
 */
case class DAG private(sortedNodes: Seq[DAGNode],
                       incomingEdgesMap: Map[NodeId, Seq[DAGEdge]],
                       startNodes: Seq[DAGNode],
                       endNodes: Seq[DAGNode]
                      )
  extends SmartDataLakeLogger {

  override def toString: String = {
    import com.github.mdr.ascii.layout._
    val edges = incomingEdgesMap.values.flatMap {
      incomingEdges => incomingEdges.map(incomingEdge => (incomingEdge.nodeIdFrom, incomingEdge.nodeIdTo))
    }
    val g = new Graph(vertices = sortedNodes.map(_.nodeId).toSet, edges = edges.toList)
    GraphLayout.renderGraph(g)
  }

  /**
   * Build a single task that is a combination of node computations (node tasks) executed in the topological order
   * defined by the DAG.
   *
   * Monix tasks is a library for lazy cancelable futures
   *
   * @note This method does not trigger any execution but builds a complex collection of tasks and synchronization
   *       boundaries that specify a correct order of execution as defined by the DAG.
   *       The computation only runs when the returning task is scheduled for execution.
   *
   * @see https://medium.com/@sderosiaux/are-scala-futures-the-past-69bd62b9c001
   *
   * @param operation A function that computes the result ([[DAGResult]]) for the current node,
   *                  given the result of its predecessors given.
   * @param scheduler The [[Scheduler]] to use for Tasks.
   *
   * @return
   */
  def buildTaskGraph[A <: DAGResult](operation: (DAGNode, Seq[A]) => Seq[A])(implicit scheduler: Scheduler): Task[Seq[Try[A]]] = {

    // this variable is used to stop execution on cancellation
    // using a local variable inside code of Futures is possible in Scala:
    // https://stackoverflow.com/questions/51690555/scala-treats-sharing-local-variables-in-threading-differently-from-java-how-d
    var isCancelled = false

    // get input tasks for each edge, combine them, execute operation as future task and remember it
    // this works because nodes are sorted and previous tasks therefore are prepared earlier
    val allTasksMap = sortedNodes.foldLeft(Map.empty[NodeId, Task[Try[Seq[A]]]]) {
      case (tasksAcc, node) =>
        val incomingResultsTask = collectIncomingNodeResults(tasksAcc, node)
        val currentNodeTask = incomingResultsTask.map {
          incomingResults =>
            // Now the incoming results have finished computing.
            // It's time to compute the result of the current node.
            if (isCancelled) {
              cancelledDAGResult(node)
            } else if (incomingResults.exists(_.isFailure)) {
              // a predecessor failed
              incomingFailedResult(node, incomingResults)
            } else {
              // compute the result for this node
              computeNodeOperationResult(node, operation, incomingResults)
            }
        }.memoize // calculate only once per node, then remember value
        // pass on the result of the current node
        tasksAcc + (node.nodeId -> currentNodeTask)
    }

    // prepare final task (Future) by combining all (independent) endNodes in the DAG
    val endTasks = endNodes.map(n => allTasksMap(n.nodeId))
    // wait for all end tasks to complete, then return a sequence
    val flattenedResult = Task.gatherUnordered(endTasks).map(_.flatMap(trySeqToSeqTry))
    flattenedResult
      .memoize
      .doOnCancel(Task {
        logger.info("DAG execution is cancelled")
        isCancelled = true
      })
  }

  private def computeNodeOperationResult[A <: DAGResult](node: DAGNode, operation: (DAGNode, Seq[A]) => Seq[A], incomingResults: Seq[Try[A]]) = {
    //onNodeStart
    val result = Try(operation(node, incomingResults.map(_.get))) // or should we use "Try( blocking { operation(node, v) })"
    result match {
      case Failure(ex) =>
        //onNodeFailure
        logger.error(s"Task ${node.nodeId} failed: $ex")
      case _ =>
        //onNodeSucces
    }
    // return
    result
  }

  private def incomingFailedResult[A <: DAGResult](node: DAGNode, incomingResults: Seq[Try[A]]) = {
    val firstPredecessorException = incomingResults.find(_.isFailure).get.failed.get
    logger.debug(s"Task ${node.nodeId} is not executed because some predecessor had error $firstPredecessorException")
    Failure(TaskPredecessorFailureException(node.nodeId, firstPredecessorException))
  }

  private def cancelledDAGResult[A <: DAGResult](node: DAGNode) = {
    logger.debug(s"Task ${node.nodeId} is cancelled because DAG execution is cancelled")
    Failure(TaskCancelledException(node.nodeId))
  }

  /**
   * Create Tasks that computes incoming results in parallel and waits for the of the incoming tasks to finish.
   *
   * @return The results of the incoming tasks.
   */
  private def collectIncomingNodeResults[A <: DAGResult](tasksAcc: Map[NodeId, Task[Try[Seq[A]]]], node: DAGNode): Task[Seq[Try[A]]] = {
    val incomingTasks = incomingEdgesMap.getOrElse(node.nodeId, Seq.empty) map { incomingEdge =>
      getResultTask(tasksAcc, incomingEdge.nodeIdFrom, incomingEdge.resultId)
    }
    // Wait for results from incoming tasks to be computed and return their results
    Task.gatherUnordered(incomingTasks)
  }

  /**
   * Convert a [[Try]] of a Result-List to a List of Result-[[Try]]'s
   */
  def trySeqToSeqTry[A](trySeq: Try[Seq[A]]): Seq[Try[A]] = trySeq match {
    case Success(result) => result.map(result => Success(result))
    case Failure(ex) => Seq(Failure(ex))
  }

  /**
   *
   * Create a task that fetches a specific [[DAGResult]] produced by the node with id `nodeId`.
   *
   * @param tasks A map of tasks that compute (future) results indexed by node.
   * @param nodeId The id of the producing node.
   * @param resultId The id of the result to search among all nodes results.
   * @tparam A The result type - supertype [[DAGResult]] ensures it has a resultId defined.
   * @return The task that computes the result specified by `nodeId` and `resultId`.
   */
  def getResultTask[A <: DAGResult](tasks: Map[NodeId, Task[Try[Seq[A]]]], nodeId: NodeId, resultId: String): Task[Try[A]] = {
    //look for already computed results of the node
    val nodeResults = tasks(nodeId)
    nodeResults map {
      resultTry => resultTry.map {
        result => result.find( _.resultId == resultId).getOrElse(throw new IllegalStateException(s"Result for incoming edge $nodeId, $resultId not found"))
      }
    }
  }
}

object DAG extends SmartDataLakeLogger {
  def create(nodes: Seq[DAGNode], edges: Seq[DAGEdge]): DAG = {
    val incomingIds = buildIncomingIdLookupTable(nodes, edges)
    // start nodes = all nodes without incoming edges
    val startNodes = incomingIds.filter(_._2.isEmpty)

    // end nodes = all nodes without outgoing edges
    val endNodes = buidlOutgoingIdLookupTable(nodes, edges).filter(_._2.isEmpty)

    // sort node IDs topologically and check there are no loops
    val sortedNodeIds = sortStep(incomingIds.map {
      case (node, inIds) => (node.nodeId, inIds)
    })
    logger.info(s"DAG node order is: ${sortedNodeIds.mkString(" -> ")}")

    //lookup table to retrieve nodes by their ID
    val nodeIdsToNodeMap = nodes.map(n => (n.nodeId, n)).toMap
    val sortedNodes = sortedNodeIds.map(nodeIdsToNodeMap)

    //lookup table to retrieve edges by ID pairs (fromId, toId)
    val edgeIdPairToEdgeMap = edges.map(e => (e.nodeIdFrom, e.nodeIdTo) -> e).toMap
    val incomingEdgesMap = incomingIds.map {
      case (node, incomingIds) => (node.nodeId, incomingIds.map(incomingId => edgeIdPairToEdgeMap(incomingId,node.nodeId)))
    }

    DAG(sortedNodes, incomingEdgesMap, startNodes.keys.toSeq, endNodes.keys.toSeq)
  }

  /**
   * Create a lookup table to retrieve outgoing (target) node IDs for a node.
   */
  private def buidlOutgoingIdLookupTable(nodes: Seq[DAGNode], edges: Seq[DAGEdge]) = {
    val targetIDsforIncommingIDsMap = edges.groupBy(_.nodeIdFrom).mapValues(_.map(_.nodeIdTo))
    nodes.map(n => (n, targetIDsforIncommingIDsMap.getOrElse(n.nodeId, Seq()))).toMap
  }

  /**
   * Create a lookup table to retrieve incoming (source) node IDs for a node.
   */
  private def buildIncomingIdLookupTable(nodes: Seq[DAGNode], edges: Seq[DAGEdge]) = {
    val incomingIDsForTargetIDMap = edges.groupBy(_.nodeIdTo).mapValues(_.map(_.nodeIdFrom))
    nodes.map(n => (n, incomingIDsForTargetIDMap.getOrElse(n.nodeId, Seq.empty))).toMap
  }

  /**
   * Sort Graph in topological order.
   *
   * Sort by recursively searching the start nodes and removing them for the next call.
   */
  def sortStep(incomingIds: Map[NodeId, Seq[NodeId]]): Seq[NodeId] = {
    // recursion stop condition
    if(incomingIds.isEmpty) return Seq.empty
    // search start nodes = nodes without incoming nodes
    val (startNodeIds, remainingNodeIds) = incomingIds.partition(_._2.isEmpty)
    assert(startNodeIds.nonEmpty, s"Loop detected in remaining nodes ${incomingIds.keys.mkString(", ")}")
    // remove start nodes from incoming node list of remaining nodes
    val remainingNodeIdsWithoutIncomingStartNodes = remainingNodeIds.mapValues {
      inIds => inIds.diff(startNodeIds.keys.toSeq)
    }
    //recursively sort remaining IDs
    startNodeIds.keys.toSeq ++ sortStep(remainingNodeIdsWithoutIncomingStartNodes)
  }
}
