package jkugiya.moneytransfer.impl

import akka.cluster.sharding.typed.scaladsl.Entity
import akka.util.Timeout
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.jdbc.JdbcPersistenceComponents
import com.lightbend.lagom.scaladsl.playjson.{
  EmptyJsonSerializerRegistry,
  JsonSerializerRegistry
}
import com.lightbend.lagom.scaladsl.server._
import com.softwaremill.macwire._
import jkugiya.moneytransfer.api.AccountService
import play.api.db.HikariCPComponents
import play.api.libs.ws.ahc.AhcWSComponents

import scala.concurrent.duration.DurationInt

class MoneytransferLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new MoneytransferApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new MoneytransferApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[AccountService])
}

abstract class MoneytransferApplication(context: LagomApplicationContext)
    extends LagomApplication(context)
    with JdbcPersistenceComponents
    with HikariCPComponents
    with LagomKafkaComponents
    with AhcWSComponents {

  implicit val timeout: Timeout = 3.seconds
  // Bind the service that this server provides
  override lazy val lagomServer: LagomServer =
    serverFor[AccountService](wire[AccountServiceImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry: JsonSerializerRegistry =
    MoneytransferSerializerRegistry

  // Initialize the sharding of the Aggregate. The following starts the aggregate Behavior under
  // a given sharding entity typeKey.
  clusterSharding.init(
    Entity(Account.TypeKey)(entityContext => Account.create(entityContext))
  )
  clusterSharding.init(
    Entity(MoneyTransfer.TypeKey)(
      entityContext => MoneyTransfer.create(entityContext)
    )
  )

}
