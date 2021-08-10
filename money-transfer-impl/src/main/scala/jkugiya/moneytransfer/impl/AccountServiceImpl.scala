package jkugiya.moneytransfer.impl

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import jkugiya.moneytransfer.api.AccountService

import scala.concurrent.ExecutionContext

class AccountServiceImpl(clusterSharding: ClusterSharding)(implicit ec: ExecutionContext, timeout: Timeout) extends AccountService {

  override def debit(customerId: Int): ServiceCall[AccountService.Debit, AccountService.DebitResult] = ServiceCall { debit =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[Account.Debit.Result](ref => Account.Debit(debit.amount, ref)).map {
      case Account.Debit.Accepted(_, _) =>
        AccountService.DebitResult(isOK = true)
      case Account.Debit.Denied(_) =>
        AccountService.DebitResult(isOK = false)
    }
  }


  override def credit(customerId: Int): ServiceCall[AccountService.Credit, NotUsed] = ServiceCall { credit =>
    val ref = clusterSharding.entityRefFor(Account.TypeKey, customerId.toString)
    ref.ask[Done](ref => Account.Credit(credit.amount, ref)).map { _ =>
         NotUsed
    }
  }
}