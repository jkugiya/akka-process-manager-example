package jkugiya.moneytransfer.impl

import akka.Done
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.lightbend.lagom.scaladsl.persistence.slick.SlickReadSide
import com.lightbend.lagom.scaladsl.persistence.{
  AggregateEventTag,
  EventStreamElement,
  ReadSideProcessor
}
import org.slf4j.LoggerFactory
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}

class MoneyTransferEventProcessor(
  clusterSharding: ClusterSharding,
  readSide: SlickReadSide
)(implicit ec: ExecutionContext, timeout: Timeout)
    extends ReadSideProcessor[MoneyTransfer.Event] {
  private val logger = LoggerFactory.getLogger(getClass)

  def moneyTransferRef[E <: MoneyTransfer.Event](elm: EventStreamElement[E]) =
    clusterSharding.entityRefFor(MoneyTransfer.TypeKey, elm.entityId)
  def handle[E <: MoneyTransfer.Event](
    elm: EventStreamElement[E]
  )(f: E => Future[Done]) =
    DBIO.from(f(elm.event))

  private val builder =
    readSide
      .builder[MoneyTransfer.Event]("money_transfer_offset")
      .setEventHandler[MoneyTransfer.Event.DebitStarted](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.DebitStarted(from, _, amount, _) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, from.toString)
              (for {
                result <- accountRef.ask[Account.Command.Debit.Result](
                  ref => Account.Command.Debit(amount = amount, ref = ref)
                )
                _ <- result match {
                  case Account.Command.Debit.Accepted(_, _) =>
                    moneyTransferRef(elm).ask[MoneyTransfer.Confirmation](
                      MoneyTransfer.Command.StartCredit
                    )
                  case Account.Command.Debit.Denied(_) =>
                    logger.error("Debit denied.")
                    moneyTransferRef(elm)
                      .ask[MoneyTransfer.Confirmation](
                        MoneyTransfer.Command.ProcessDebitFail
                      )
                }
              } yield Done).recoverWith { t =>
                {
                  logger.error("Failed to handle DebitStarted", t)
                  moneyTransferRef(elm)
                    .ask[MoneyTransfer.Confirmation](
                      MoneyTransfer.Command.ProcessDebitFail
                    )
                    .map(_ => Done)
                }
              }
        }
      )
      .setEventHandler[MoneyTransfer.Event.CreditStarted](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.CreditStarted(_, to, amount) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, to.toString)
              (for {
                _ <- accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                _ <- moneyTransferRef(elm).ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.ProcessSuccess
                )
              } yield Done).recoverWith(t => {
                logger.error("Failed to handle CreditStarted", t)
                moneyTransferRef(elm)
                  .ask[MoneyTransfer.Confirmation](
                    MoneyTransfer.Command.ProcessDebitRollbackFail
                  )
                  .map(_ => Done)
              })
        }
      )
      .setEventHandler[MoneyTransfer.Event.DebitRollbackStarted](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.DebitRollbackStarted(from, _, amount) =>
              val accountRef =
                clusterSharding.entityRefFor(Account.TypeKey, from.toString)
              (for {
                _ <- accountRef.ask[Done](
                  ref => Account.Command.Credit(amount = amount, ref = ref)
                )
                _ <- moneyTransferRef(elm).ask[MoneyTransfer.Confirmation](
                  MoneyTransfer.Command.ProcessDebitRollbacked
                )
              } yield Done)
                .recoverWith(t => {
                  logger.error("Failed to handle DebitRollbackStarted", t)
                  moneyTransferRef(elm)
                    .ask[MoneyTransfer.Confirmation](
                      MoneyTransfer.Command.ProcessDebitRollbackFail
                    )
                    .map(_ => Done)
                })
        }
      )
      .setEventHandler[MoneyTransfer.Event.DebitFailed](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.DebitFailed(_, _, _, ref) =>
              ref ! MoneyTransfer.Confirmation.NG
              Future.successful(Done)
          })
      .setEventHandler[MoneyTransfer.Event.DebitRollbacked](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.DebitRollbacked(_, _, _, ref) =>
              ref ! MoneyTransfer.Confirmation.NG
              Future.successful(Done)
          })
      .setEventHandler[MoneyTransfer.Event.DebitRollbackFailed](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.DebitRollbackFailed(_, _, _, ref) =>
              ref ! MoneyTransfer.Confirmation.NG
              Future.successful(Done)
          })
      .setEventHandler[MoneyTransfer.Event.Succeeded](
        elm =>
          handle(elm) {
            case MoneyTransfer.Event.Succeeded(_, _, _, ref) =>
              ref ! MoneyTransfer.Confirmation.OK
              Future.successful(Done)
          })

  override def buildHandler()
    : ReadSideProcessor.ReadSideHandler[MoneyTransfer.Event] = builder.build()

  override def aggregateTags: Set[AggregateEventTag[MoneyTransfer.Event]] =
    Set(MoneyTransfer.Event.Tag)

}
