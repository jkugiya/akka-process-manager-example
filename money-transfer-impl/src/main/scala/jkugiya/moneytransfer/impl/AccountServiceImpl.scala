package jkugiya.moneytransfer.impl

import akka.{Done, NotUsed}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.lightbend.lagom.scaladsl.api.ServiceCall
import jkugiya.moneytransfer.api.AccountService
import jkugiya.moneytransfer.api.AccountService.TransferResult
import jkugiya.moneytransfer.impl.Account.Command.Credit

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class AccountServiceImpl(clusterSharding: ClusterSharding)(
  implicit ec: ExecutionContext,
  timeout: Timeout
) extends AccountService {

  override def debit(
    customerId: Int
  ): ServiceCall[AccountService.Debit, AccountService.DebitResult] =
    ServiceCall { debit =>
      val ref =
        clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
      val transactionId = UUID.randomUUID() // TODO transactionId should be given by the client
      for {
        result <- ref.ask[Account.Command.Debit.Result](
          ref => Account.Command.Debit(transactionId, debit.amount, ref)
        )
        isOK <- result match {
          case Account.Command.Debit.Accepted(_, _) =>
            ref.ask[Done](
              ref => Account.Command.AcceptTransaction(transactionId, ref)
            ).map(_ => true)
          case Account.Command.Debit.Denied(_) =>
            Future.successful(false)
        }
      } yield AccountService.DebitResult(isOK)
    }

  override def credit(
    customerId: Int
  ): ServiceCall[AccountService.Credit, NotUsed] = ServiceCall { credit =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    val transactionId = UUID.randomUUID() // TODO transactionId should be given by the client
    for {
      _ <- ref
          .ask[Credit.Result](
            ref => Account.Command.Credit(transactionId, credit.amount, ref)
          )
      _ <-
        ref.ask[Done](
          ref => Account.Command.AcceptTransaction(transactionId, ref)
        ).map(_ => true)
    } yield NotUsed
  }

  override def get(
    customerId: Int
  ): ServiceCall[NotUsed, AccountService.Balance] = ServiceCall { _ =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[BigDecimal](handler => Account.Command.Get(handler)).map { result =>
      AccountService.Balance(result)
    }
  }

  override def transfer: ServiceCall[AccountService.Transfer, TransferResult] =
    ServiceCall { transfer =>
      val id = UUID.randomUUID()
      val ref = clusterSharding.entityRefFor(MoneyTransfer.TypeKey, id.toString)
      ref
        .ask[MoneyTransfer.Confirmation](
          ref =>
            MoneyTransfer.Command.StartTransfer(
              from = transfer.from,
              to = transfer.to,
              amount = transfer.amount,
              ref = ref
          )
        )
        .map {
          case MoneyTransfer.Confirmation.OK =>
            TransferResult(true)
          case MoneyTransfer.Confirmation.NG =>
            TransferResult(false)
        }
    }
}
