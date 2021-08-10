package jkugiya.moneytransfer.impl

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.ReplyEffect
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import jkugiya.moneytransfer.impl.MoneyTransfer.{Command, Event, Status}
import play.api.libs.json._

import java.util.UUID

case class MoneyTransfer(id: UUID,
                         debitResult: Option[Boolean],
                         creditResult: Option[Boolean],
                         status: Status) {

  import Command._
  def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer] =
    cmd match {
      case StartDebit(from, amount) =>
        Effect.persist(Event.DebitStarted(from, amount)).thenNoReply()
      case StartCredit(to, amount) =>
        Effect.persist(Event.CreditStarted(to, amount)).thenNoReply()
      case StartDebitRollback(from, amount) =>
        Effect.persist(Event.DebitRollbackStarted(from, amount)).thenNoReply()
      case ProcessDebitFail(from, amount) =>
        Effect.persist(Event.DebitFailed(from, amount)).thenNoReply()
      case ProcessDebitRollbackFail(from, amount) =>
        Effect.persist(Event.DebitRollbackFailed(from, amount)).thenNoReply()
      case ProcessSuccess(from, to, amount) =>
        Effect.persist(Event.Succeeded(from, to, amount)).thenNoReply()
    }

  import Event._
  def applyEvent(evt: Event): MoneyTransfer = evt match {
    case DebitStarted(_, _) =>
      MoneyTransfer(
        id = id,
        debitResult = None,
        creditResult = None,
        status = Status.DebitStarted
      )
    case DebitFailed(_, _) =>
      MoneyTransfer(
        id = id,
        debitResult = Option(false),
        creditResult = None,
        status = Status.DebitFailed
      )
    case CreditStarted(_, _) =>
      MoneyTransfer(
        id = id,
        debitResult = Option(true),
        creditResult = None,
        status = Status.CreditStarted
      )
    case DebitRollbackStarted(_, _) =>
      MoneyTransfer(
        id = id,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackStarted
      )
    case DebitRollbackFailed(_, _) =>
      MoneyTransfer(
        id = id,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackFailed
      )
    case Succeeded(_, _, _) =>
      MoneyTransfer(
        id = id,
        debitResult = Option(true),
        creditResult = Option(true),
        status = Status.Succeeded
      )
  }
}

object MoneyTransfer {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("money_transfer")

  sealed trait Confirmation
  object Confirmation {
    case object OK extends Confirmation
    case object NG extends Confirmation
    implicit val format: Format[Confirmation] = Format(
      Reads[Confirmation] {
        case JsObject(elems) if elems.contains("result")=>
          JsSuccess {
            elems.get("result").map {
              case JsString("ok") => OK
              case _ => NG
            }.getOrElse(NG)
          }
        case _ => JsSuccess(NG)
      },
      Writes[Confirmation] {
        case OK => JsObject(Seq("result" -> JsString("ok")))
        case NG => JsObject(Seq("result" -> JsString("ng")))
      }
    )
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
  sealed trait Command
  object Command {
    case class StartDebit(from: Int, amount: BigDecimal) extends Command
    case class StartCredit(to: Int, amount: BigDecimal) extends Command
    case class StartDebitRollback(from: Int, amount: BigDecimal) extends Command
    case class ProcessDebitFail(from: Int, amount: BigDecimal) extends Command
    case class ProcessDebitRollbackFail(from: Int, amount: BigDecimal)
        extends Command
    case class ProcessSuccess(from: Int, to: Int, amount: BigDecimal)
        extends Command
  }

  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    case class DebitStarted(from: Int, amount: BigDecimal) extends Event
    object DebitStarted {
      implicit val format: Format[DebitStarted] = Json.format
    }
    case class DebitFailed(from: Int, amount: BigDecimal) extends Event
    object DebitFailed {
      implicit val format: Format[DebitFailed] = Json.format
    }
    case class CreditStarted(to: Int, amount: BigDecimal) extends Event
    object CreditStarted {
      implicit val format: Format[CreditStarted] = Json.format
    }
    case class DebitRollbackStarted(from: Int, amount: BigDecimal) extends Event
    object DebitRollbackStarted {
      implicit val format: Format[DebitRollbackStarted] = Json.format
    }
    case class DebitRollbackFailed(from: Int, amount: BigDecimal) extends Event
    object DebitRollbackFailed {
      implicit val format: Format[DebitRollbackFailed] = Json.format
    }
    case class Succeeded(from: Int, to: Int, amount: BigDecimal) extends Event
    object Succeeded {
      implicit val format: Format[Succeeded] = Json.format
    }
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
        emptyState = MoneyTransfer(
          id = id,
          debitResult = None,
          creditResult = None,
          status = Status.Init
        ),
        commandHandler = (transfer, cmd) => transfer.applyCommand(cmd),
        eventHandler = (transfer, evt) => transfer.applyEvent(evt)
      )

  implicit val format: Format[MoneyTransfer] = Json.format
}
