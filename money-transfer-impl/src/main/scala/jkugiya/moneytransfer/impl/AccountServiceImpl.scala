package jkugiya.moneytransfer.impl

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import jkugiya.moneytransfer.api.AccountService
import jkugiya.moneytransfer.api.AccountService.TransferResult

import java.util.UUID
import scala.concurrent.ExecutionContext

class AccountServiceImpl(clusterSharding: ClusterSharding)(implicit ec: ExecutionContext, timeout: Timeout) extends AccountService {

  override def debit(customerId: Int): ServiceCall[AccountService.Debit, AccountService.DebitResult] = ServiceCall { debit =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[Account.Command.Debit.Result](ref => Account.Command.Debit(debit.amount, ref)).map {
      case Account.Command.Debit.Accepted(_, _) =>
        AccountService.DebitResult(isOK = true)
      case Account.Command.Debit.Denied(_) =>
        AccountService.DebitResult(isOK = false)
    }
  }


  override def credit(customerId: Int): ServiceCall[AccountService.Credit, NotUsed] = ServiceCall { credit =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[Done](ref => Account.Command.Credit(credit.amount, ref)).map { _ =>
         NotUsed
    }
  }

  override def get(customerId: Int): ServiceCall[NotUsed, AccountService.Balance] = ServiceCall { _ =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[BigDecimal](handler => Account.Command.Get(handler)).map { result =>
      AccountService.Balance(result)
    }
  }

  override def transfer: ServiceCall[AccountService.Transfer, TransferResult] = ServiceCall { transfer =>
    val id = UUID.randomUUID()
    val ref = clusterSharding.entityRefFor(MoneyTransfer.TypeKey, id.toString)
    ref.ask[MoneyTransfer.Confirmation](ref => MoneyTransfer.Command.StartTransfer(
      from = transfer.from, to = transfer.to, amount = transfer.amount, ref = ref
    )).map {
      case MoneyTransfer.Confirmation.OK =>
        TransferResult(true)
      case MoneyTransfer.Confirmation.NG =>
        TransferResult(false)
    }
  }
}
