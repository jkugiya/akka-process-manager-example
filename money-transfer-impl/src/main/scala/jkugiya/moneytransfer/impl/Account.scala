package jkugiya.moneytransfer.impl

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AkkaTaggerAdapter}
import jkugiya.moneytransfer.impl.Account.{Command, Event}
import org.slf4j.LoggerFactory
import play.api.libs.json.{Format, Json}

import java.util.UUID

case class Account(userId: Int, amount: BigDecimal, transactionId: Option[UUID]) {
  private val logger = LoggerFactory.getLogger(getClass)
  import Command._
  def applyCommand(cmd: Command): ReplyEffect[Event, Account] = cmd match {
    case Credit(tid, orderedAmount, ref) =>
      transactionId.fold[ReplyEffect[Event, Account]](
        ifEmpty =
          Effect
            .persist(Event.CreditAccepted(tid, userId, before = amount, amount = orderedAmount))
            .thenReply(ref)(_ => Credit.Accepted)
      ) { transactionId =>
        if (transactionId == tid) {
          Effect
            .persist(Event.CreditAccepted(tid, userId, before = amount, amount = orderedAmount))
            .thenReply(ref)(_ => Credit.Accepted)
        } else {
          Effect.reply(ref)(Credit.Denied)
        }
      }
    case Debit(tid, orderedAmount, ref) =>
      def processDebit(tid: UUID): ReplyEffect[Event, Account] =
        if (orderedAmount > amount) {
          logger.info(s"Debit Accepting ${orderedAmount}, ${amount}")
          Effect.persist(Event.DebitDenied(userId))
            .thenReply(ref)(_ => Debit.Denied(amount))
        } else {
          logger.info(s"Debit Denying ${orderedAmount}, ${amount}")
          Effect.persist(Event.DebitAccepted(tid, userId, before = amount, amount = orderedAmount))
            .thenReply(ref)(_ => Debit.Accepted(amount, orderedAmount))
        }
      transactionId.fold[ReplyEffect[Event, Account]](
        ifEmpty = processDebit(tid)
      ) { transactionId =>
        if (transactionId == tid) processDebit(tid)
        else {
          Effect.reply(ref)(Debit.Denied(amount))
        }
      }
    case Get(ref) =>
      Effect.reply(ref)(amount)
    case AcceptTransaction(transactionId, ref) =>
      Effect.persist(Event.TransactionAccepted(transactionId)).thenReply(ref)(_ => Done)
  }

  import Event._
  def applyEvent(event: Event): Account = event match {
    case DebitAccepted(transactionId, _, before, orderedAmount) =>
      logger.info(s"DebitAccepted. userId=$userId, before=$before, after=${before - orderedAmount}")
      Account(userId, before - orderedAmount, Option(transactionId))
    case CreditAccepted(transactionId, _, before, orderedAmount) =>
      logger.info(s"CreditAccepted. userId=$userId, before=$before, after=${before + orderedAmount}")
      Account(userId, before + orderedAmount, Option(transactionId))
    case DebitDenied(_) =>
      this
    case TransactionAccepted(tid) =>
      Account(userId, amount, transactionId.filterNot(_ == tid))
  }
}

sealed trait AccountCommandSerializable
object Account {
  def create(entityContext: EntityContext[Command]): Behavior[Command] = {
    val persistenceId= PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    val userId = entityContext.entityId.toInt
    create(userId, persistenceId)
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))
  }
  private def create(userId: Int, persistenceId: PersistenceId) =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, Account](
        persistenceId = persistenceId,
        emptyState = Account(userId, BigDecimal(0), None),
        commandHandler = (account, cmd) => account.applyCommand(cmd),
        eventHandler = (account, evt) => account.applyEvent(evt)
      )

  sealed trait Command extends AccountCommandSerializable
  object Command {
    case class Debit(transactionId: UUID, amount: BigDecimal, ref: ActorRef[Debit.Result]) extends Command
    object Debit {
      sealed trait Result
      case class Accepted(before: BigDecimal, amount: BigDecimal) extends Result
      case class Denied(current: BigDecimal) extends Result
    }
    case class Credit(transactionId: UUID, amount: BigDecimal, ref: ActorRef[Credit.Result]) extends Command
    object Credit {
      sealed trait Result
      case object Accepted extends Result
      case object Denied extends Result

      def accepted(): Result = Accepted
      def denied(): Result = Denied
    }
    case class AcceptTransaction(transactionId: UUID, ref: ActorRef[Done]) extends Command
    case class Get(ref: ActorRef[BigDecimal]) extends Command
  }

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("account")

  sealed trait Event extends AggregateEvent[Event] {
    def aggregateTag: AggregateEventTag[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventTag[Event] = AggregateEventTag[Event]
    case class DebitAccepted(transactionId: UUID, userId: Int, before: BigDecimal, amount: BigDecimal) extends Event
    object DebitAccepted {
      implicit val format: Format[DebitAccepted] = Json.format
    }
    case class CreditAccepted(transactionId: UUID, userId: Int, before: BigDecimal, amount: BigDecimal) extends Event
    object CreditAccepted {
      implicit val format: Format[CreditAccepted] = Json.format
    }
    case class TransactionAccepted(transactionId: UUID) extends Event
    object TransactionAccepted {
      implicit val format: Format[TransactionAccepted] = Json.format
    }
    case class DebitDenied(userId: Int) extends Event
    object DebitDenied {
      implicit val format: Format[DebitDenied] = Json.format
    }
  }

  implicit val format: Format[Account] = Json.format
}
