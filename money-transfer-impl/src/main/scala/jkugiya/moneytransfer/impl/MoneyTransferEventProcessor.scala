package jkugiya.moneytransfer.impl

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, EventStreamElement, ReadSideProcessor}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class MoneyTransferEventProcessor(clusterSharding: ClusterSharding)(
  implicit ec: ExecutionContext,
  timeout: Timeout
) extends ReadSideProcessor[MoneyTransfer.Event] {

  override def buildHandler()
    : ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] =
    new ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] {
      override def handle()
        : Flow[EventStreamElement[MoneyTransfer.Event], Done, NotUsed] =
        Flow[EventStreamElement[MoneyTransfer.Event]].mapAsync(1) {
          eventElement =>
            val entityId = eventElement.entityId
            val moneyTransferRef =
              clusterSharding.entityRefFor(MoneyTransfer.TypeKey, entityId)
            eventElement.event match {
              case MoneyTransfer.Event.DebitStarted(from, to, amount) =>
                val accountRef =
                  clusterSharding.entityRefFor(Account.TypeKey, from.toString)
                val future = accountRef.ask[Account.Command.Debit.Result](
                  ref => Account.Command.Debit(amount = amount, ref = ref)
                )
                future.transform {
                  case Success(Account.Command.Debit.Accepted(_, _)) =>
                    moneyTransferRef ! MoneyTransfer.Command.StartCredit
                    Success(Done)
                  case _ =>
                    moneyTransferRef ! MoneyTransfer.Command
                      .ProcessDebitFail
                    Success(Done)
                }
              case MoneyTransfer.Event.CreditStarted(from, to, amount) =>
                val accountRef =
                  clusterSharding.entityRefFor(Account.TypeKey, to.toString)
                val future = accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                future.transform {
                  case Success(_) =>
                    moneyTransferRef ! MoneyTransfer.Command.ProcessSuccess
                    Success(Done)
                  case Failure(_) =>
                    moneyTransferRef ! MoneyTransfer.Command
                      .StartDebitRollback
                    Success(Done)
                }
              case MoneyTransfer.Event.DebitFailed(from, _, _) =>
                moneyTransferRef ! MoneyTransfer.Command.ProcessDebitFail
                Future.successful(Done)
              case MoneyTransfer.Event.DebitRollbackStarted(from, _, amount) =>
                val accountRef =
                  clusterSharding.entityRefFor(Account.TypeKey, from.toString)
                val future = accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                future.transform {
                  case Success(_) =>
                    moneyTransferRef ! MoneyTransfer.Command.ProcessDebitRollbacked
                    Success(Done)
                  case _ =>
                    moneyTransferRef ! MoneyTransfer.Command.ProcessDebitRollbackFail
                    Success(Done)
                }
              case MoneyTransfer.Event.DebitRollbacked(_, _, _) =>
                Future.successful(Done)
              case MoneyTransfer.Event.DebitRollbackFailed(_, _, _) =>
                Future.successful(Done)
              case MoneyTransfer.Event.Succeeded(_, _, _) =>
                Future.successful(Done)
            }
        }
    }

  override def aggregateTags: Set[AggregateEventTag[MoneyTransfer.Event]] =
    Set(MoneyTransfer.Event.Tag)

}
