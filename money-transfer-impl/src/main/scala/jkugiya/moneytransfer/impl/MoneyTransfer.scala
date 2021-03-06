package jkugiya.moneytransfer.impl

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import jkugiya.moneytransfer.impl.MoneyTransfer.Command.StartTransfer
import jkugiya.moneytransfer.impl.MoneyTransfer.Event.DebitStarted
import jkugiya.moneytransfer.impl.MoneyTransfer.{Command, Confirmation, Event, Status}
import org.slf4j.LoggerFactory
import play.api.libs.json._

import java.util.UUID

sealed trait MoneyTransfer {
  def id: UUID
  def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer]
  def applyEvent(event: Event): MoneyTransfer
}
case class InitialState(id: UUID) extends MoneyTransfer {
  private val logger = LoggerFactory.getLogger(getClass)

  override def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer] = cmd match {
    case StartTransfer(from, to, amount, ref) =>
      logger.info(s"Started Transfer.")
      Effect
        .persist(Event.DebitStarted(id, from, to, amount, ref))
        .thenNoReply()
    case cmd =>
      Effect.reply(cmd.ref)(Confirmation.NG)
  }

  override def applyEvent(event: Event): MoneyTransfer = event match {
    case DebitStarted(_, from, to, amount, replyTo) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to, amount = amount,
        debitResult = None,
        creditResult = None,
        status = MoneyTransfer.Status.DebitStarted,
        replyTo = replyTo
      )
  }
}
case class RunningMoneyTransfer(id: UUID,
                         from: Int,
                         to: Int,
                         amount: BigDecimal,
                         debitResult: Option[Boolean],
                         creditResult: Option[Boolean],
                         status: Status,
                         replyTo: ActorRef[Confirmation]) extends MoneyTransfer {

  private val logger = LoggerFactory.getLogger(getClass)

  import Command._
  def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer] =
    cmd match {
      case StartTransfer(from, to, amount, ref) =>
        logger.info(s"Started Transfer.")
        Effect
          .persist(Event.DebitStarted(id, from, to, amount, ref))
          .thenNoReply()
      case StartCredit(ref: ActorRef[Confirmation]) =>
        logger.info(s"Started Credit.")
        Effect
          .persist(Event.CreditStarted(id, from, to, amount))
          .thenReply(ref)(_ => Confirmation.OK)
      case StartDebitRollback(ref: ActorRef[Confirmation]) =>
        logger.info(s"Started DebitRollback.")
        Effect
          .persist(Event.DebitRollbackStarted(id, from, to, amount))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitFail(ref: ActorRef[Confirmation]) =>
        logger.info(s"Debit failed")
        Effect
          .persist[Event, MoneyTransfer](Event.DebitFailed(id, from, to, amount, replyTo))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitRollbacked(ref: ActorRef[Confirmation]) =>
        logger.info(s"Debit Rollbacked")
        Effect
          .persist[Event, MoneyTransfer](Event.DebitRollbacked(id, from, to, amount, replyTo))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitRollbackFail(ref: ActorRef[Confirmation]) =>
        logger.info(s"DebitRollback failed")
        Effect
          .persist[Event, MoneyTransfer](Event.DebitRollbackFailed(id, from, to, amount, replyTo))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessSuccess(ref: ActorRef[Confirmation]) =>
        logger.info(s"MoneyTransfer Succeeded.")
        Effect
          .persist[Event, MoneyTransfer](Event.Succeeded(id, from, to, amount, replyTo))
          .thenReply(ref)(_ => Confirmation.OK)
    }

  import Event._
  def applyEvent(evt: Event): MoneyTransfer = evt match {
    case DebitStarted(_, from, to, amount, replyTo) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = None,
        creditResult = None,
        status = Status.DebitStarted,
        replyTo = replyTo
      )
    case DebitFailed(_, _, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(false),
        creditResult = None,
        status = Status.DebitFailed,
        replyTo = replyTo
      )
    case CreditStarted(_, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = None,
        status = Status.CreditStarted,
        replyTo = replyTo
      )
    case DebitRollbackStarted(_, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackStarted,
        replyTo = replyTo
      )
    case DebitRollbacked(_, _, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbacked,
        replyTo = replyTo
      )
    case DebitRollbackFailed(_, _, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackFailed,
        replyTo = replyTo
      )
    case Succeeded(_, _, _, _, _) =>
      RunningMoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(true),
        status = Status.Succeeded,
        replyTo = replyTo
      )
  }
}

sealed trait MoneyTransferMessageSerializable
object MoneyTransfer {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("money_transfer")

  sealed trait Confirmation extends MoneyTransferMessageSerializable
  object Confirmation {
    case object OK extends Confirmation
    case object NG extends Confirmation
  }

  sealed trait Status
  object Status {

    case object Init extends Status
    case object DebitStarted extends Status
    case object CreditStarted extends Status
    case object DebitRollbackStarted extends Status
    case object DebitRollbacked extends Status
    case object DebitRollbackFailed extends Status
    case object DebitFailed extends Status
    case object Succeeded extends Status

    implicit val format: Format[Status] = Format(Reads[Status] {
      case JsString("init")                   => JsSuccess(Init)
      case JsString("debit_started")          => JsSuccess(DebitStarted)
      case JsString("credit_started")         => JsSuccess(CreditStarted)
      case JsString("debit_rollback_started") => JsSuccess(DebitRollbackStarted)
      case JsString("debit_failed")           => JsSuccess(DebitFailed)
      case JsString("debit_rollbacked")       => JsSuccess(DebitRollbacked)
      case JsString("debit_rollback_failed")  => JsSuccess(DebitRollbackFailed)
      case JsString("succeeded")              => JsSuccess(Succeeded)
      case _                                  => JsError()
    }, Writes[Status] {
      case Init                 => JsString("init")
      case DebitStarted         => JsString("debit_started")
      case CreditStarted        => JsString("credit_started")
      case DebitRollbackStarted => JsString("debit_rollback_started")
      case DebitFailed          => JsString("debit_failed")
      case DebitRollbacked      => JsString("debit_rollbacked")
      case DebitRollbackFailed  => JsString("debit_rollback_failed")
      case Succeeded            => JsString("succeeded")
    })
  }
  sealed trait Command extends MoneyTransferMessageSerializable {
    def ref: ActorRef[Confirmation]
  }
  object Command {
    case class StartTransfer(from: Int,
                             to: Int,
                             amount: BigDecimal,
                             ref: ActorRef[Confirmation])
        extends Command
    case class StartCredit(ref: ActorRef[Confirmation]) extends Command
    case class StartDebitRollback(ref: ActorRef[Confirmation]) extends Command
    case class ProcessDebitFail(ref: ActorRef[Confirmation]) extends Command
    case class ProcessDebitRollbacked(ref: ActorRef[Confirmation])
        extends Command
    case class ProcessDebitRollbackFail(ref: ActorRef[Confirmation])
        extends Command
    case class ProcessSuccess(ref: ActorRef[Confirmation]) extends Command
  }

  sealed trait Event extends AggregateEvent[Event] with MoneyTransferMessageSerializable {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    case class DebitStarted(transactionId: UUID, from: Int, to: Int, amount: BigDecimal, replyTo: ActorRef[Confirmation])
        extends Event
    case class DebitFailed(transactionId: UUID, from: Int, to: Int, amount: BigDecimal, replyTo: ActorRef[Confirmation]) extends Event
    case class CreditStarted(transactionId: UUID, from: Int, to: Int, amount: BigDecimal)
        extends Event
    case class DebitRollbackStarted(transactionId: UUID, from: Int, to: Int, amount: BigDecimal)
        extends Event
    case class DebitRollbackFailed(transactionId: UUID, from: Int, to: Int, amount: BigDecimal, replyTo: ActorRef[Confirmation])
        extends Event
    case class DebitRollbacked(transactionId: UUID, from: Int, to: Int, amount: BigDecimal, replyTo: ActorRef[Confirmation])
        extends Event
    case class Succeeded(transactionId: UUID, from: Int, to: Int, amount: BigDecimal, replyTo: ActorRef[Confirmation]) extends Event
    val Tag: AggregateEventTag[Event] = AggregateEventTag[Event]
  }

  def create(entityContext: EntityContext[Command]): Behavior[Command] = {
    val persistenceId =
      PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    val id = UUID.fromString(entityContext.entityId)
    create(id, persistenceId)
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))
  }

  private def create(id: UUID, persistenceId: PersistenceId) =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, MoneyTransfer](
        persistenceId = persistenceId,
        emptyState = InitialState(id),
        commandHandler = (transfer, cmd) => transfer.applyCommand(cmd),
        eventHandler = (transfer, evt) => transfer.applyEvent(evt)
      )
}
