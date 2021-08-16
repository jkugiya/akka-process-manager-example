package jkugiya.moneytransfer.impl

import akka.Done
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.lightbend.lagom.scaladsl.persistence.slick.SlickReadSide
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, ReadSideProcessor}
import slick.dbio.DBIOAction

import scala.concurrent.{ExecutionContext, Future}

class MoneyTransferEventProcessor(
  clusterSharding: ClusterSharding,
  readSide: SlickReadSide
)(implicit ec: ExecutionContext, timeout: Timeout)
    extends ReadSideProcessor[MoneyTransfer.Event] {

  private val builder =
    readSide
      .builder[MoneyTransfer.Event]("money_transfer_offset")
      .setEventHandler[MoneyTransfer.Event]({ eventElement =>
        val entityId = eventElement.entityId
        val moneyTransferRef =
          clusterSharding.entityRefFor(MoneyTransfer.TypeKey, entityId)
        DBIOAction.from {
          eventElement.event match {
            case MoneyTransfer.Event.DebitStarted(from, _, amount) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, from.toString)
              (for {
                _ <- accountRef.ask[Account.Command.Debit.Result](
                  ref => Account.Command.Debit(amount = amount, ref = ref)
                )
                _ <- moneyTransferRef.ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.StartCredit
                )
              } yield Done).fallbackTo(
                moneyTransferRef
                  .ask[MoneyTransfer.Confirmation](
                    MoneyTransfer.Command.ProcessDebitFail
                  )
                  .map(_ => Done)
              )
            case MoneyTransfer.Event.CreditStarted(_, to, amount) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, to.toString)
              (for {
                _ <- accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                _ <- moneyTransferRef.ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.StartDebitRollback
                )
              } yield Done).fallbackTo(
                moneyTransferRef
                  .ask[MoneyTransfer.Confirmation](
                    MoneyTransfer.Command.ProcessDebitRollbackFail
                  )
                  .map(_ => Done)
              )
            case MoneyTransfer.Event.DebitFailed(_, _, _) =>
              moneyTransferRef
                .ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.ProcessDebitFail
                )
                .map(_ => Done)
            case MoneyTransfer.Event.DebitRollbackStarted(from, _, amount) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, from.toString)
              (for {
                _ <- accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                _ <- moneyTransferRef.ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.ProcessDebitRollbacked
                )
              } yield Done)
                .fallbackTo(
                  moneyTransferRef.ask[MoneyTransfer.Confirmation](
                    MoneyTransfer.Command.ProcessDebitRollbackFail
                  )
                )
                .map(_ => Done)
            case MoneyTransfer.Event.DebitRollbacked(_, _, _) =>
              Future.successful(Done)
            case MoneyTransfer.Event.DebitRollbackFailed(_, _, _) =>
              Future.successful(Done)
            case MoneyTransfer.Event.Succeeded(_, _, _) =>
              Future.successful(Done)
          }
        }
      })

  override def buildHandler()
    : ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] = builder.build()

  override def aggregateTags: Set[AggregateEventTag[MoneyTransfer.Event]] =
    Set(MoneyTransfer.Event.Tag)

}
