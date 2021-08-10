package jkugiya.moneytransfer.impl

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, EventStreamElement, ReadSideProcessor}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class MoneyTransferEventProcessor(clusterSharding: ClusterSharding)(implicit ec: ExecutionContext, timeout: Timeout) extends ReadSideProcessor[MoneyTransfer.Event] {

  override def buildHandler(): ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] = new ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] {
    override def handle(): Flow[EventStreamElement[MoneyTransfer.Event], Done, NotUsed] = Flow[EventStreamElement[MoneyTransfer.Event]].mapAsync(1) { eventElement =>
    val entityId = eventElement.entityId
      eventElement.event match {
        case MoneyTransfer.Event.CreditStarted(from, amount) =>
          val ref = clusterSharding.entityRefFor(Account.TypeKey, from.toString)
          val future = ref.ask[Done](ref => Account.Command.Credit(amount = amount, ref = ref))
          future.transform {
            case Success(_) =>
              val ref = clusterSharding.entityRefFor(MoneyTransfer.TypeKey, entityId)
              ref ! MoneyTransfer.Command.ProcessSuccess(???, ???, ???)
              Success(Done)
            case Failure(exception) =>
              ref ! MoneyTransfer.Command.StartDebitRollback(???, ???)
              Success(Done)
          }
          future
      }
    }
  }

  override def aggregateTags: Set[AggregateEventTag[MoneyTransfer.Event]] = ???

}
