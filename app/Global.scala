import actors.{LocalClusterAwareActor, RouterActor}
import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging
import hq.agents.AgentsManagerActor
import hq.cluster.ClusterManagerActor
import hq.gates.GateManagerActor
import hq.routing.MessageRouterActor
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.libs.Akka

object Global extends GlobalSettings with scalalogging.StrictLogging {

  private var clusterSystem: Option[ActorSystem] = None

  override def onStart(app: Application): Unit = {

    clusterSystem.foreach(_.shutdown())

    val localSystem =  Akka.system()

    implicit val newClusterSystem =  ActorSystem("ehub",ConfigFactory.load("akka.conf"))

    clusterSystem = Some(newClusterSystem)

    implicit val cluster = Cluster(newClusterSystem)

    implicit val config = ConfigFactory.load("ehub.conf")

    implicit val ec = newClusterSystem.dispatcher

    val messageRouter = MessageRouterActor.start
    ClusterManagerActor.start

    LocalClusterAwareActor.start(cluster)(localSystem)
    RouterActor.start(messageRouter)(localSystem)

  }

  override def onStop(app: Application): Unit = {
    clusterSystem.foreach(_.shutdown())
    clusterSystem = None
    super.onStop(app)
  }

  private def getSubdomain (request: RequestHeader) = request.domain.replaceFirst("[\\.]?[^\\.]+[\\.][^\\.]+$", "")

  override def onRouteRequest (request: RequestHeader) = getSubdomain(request) match {
    case "admin" => admin.Routes.routes.lift(request)
    case _ => web.Routes.routes.lift(request)
  }

  // 404 - page not found error
  override def onHandlerNotFound (request: RequestHeader) = getSubdomain(request) match {
    case "admin" => GlobalAdmin.onHandlerNotFound(request)
    case _ => GlobalWeb.onHandlerNotFound(request)
  }

  // 500 - internal server error
  override def onError (request: RequestHeader, throwable: Throwable) = getSubdomain(request) match {
    case "admin" => GlobalAdmin.onError(request, throwable)
    case _ => GlobalWeb.onError(request, throwable)
  }

  // called when a route is found, but it was not possible to bind the request parameters
  override def onBadRequest (request: RequestHeader, error: String) = getSubdomain(request) match {
    case "admin" => GlobalAdmin.onBadRequest(request, error)
    case _ => GlobalWeb.onBadRequest(request, error)
  }


}