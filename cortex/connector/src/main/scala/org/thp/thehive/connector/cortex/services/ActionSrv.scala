package org.thp.thehive.connector.cortex.services

import java.util.Date

import akka.actor.ActorRef
import com.google.inject.name.Named
import gremlin.scala._
import io.scalaland.chimney.dsl._
import javax.inject.Inject
import org.thp.cortex.client.{CortexClient, CortexConfig}
import org.thp.cortex.dto.v0.{CortexOutputJob, InputCortexAction}
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models._
import org.thp.scalligraph.services._
import org.thp.scalligraph.{EntitySteps, NotFoundError}
import org.thp.thehive.connector.cortex.models.{Action, ActionContext, ActionOperationStatus, RichAction}
import org.thp.thehive.connector.cortex.services.CortexActor.CheckJob
import org.thp.thehive.models.{Case, TheHiveSchema}
import play.api.libs.json.{JsObject, Json, OWrites}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

class ActionSrv @Inject()(
    implicit db: Database,
    cortexConfig: CortexConfig,
    implicit val ex: ExecutionContext,
    @Named("cortex-actor") cortexActor: ActorRef,
    actionOperationSrv: ActionOperationSrv,
    schema: TheHiveSchema,
    entityHelper: EntityHelper
) extends VertexSrv[Action, ActionSteps] {

  val actionContextSrv = new EdgeSrv[ActionContext, Action, Product]

  import org.thp.thehive.connector.cortex.controllers.v0.ActionOperationConversion._
  import org.thp.thehive.connector.cortex.controllers.v0.JobConversion._

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): ActionSteps = new ActionSteps(raw)

  def toCortexAction(action: Action, label: String, tlp: Int, pap: Int, data: JsObject): InputCortexAction =
    action
      .into[InputCortexAction]
      .withFieldConst(_.dataType, s"thehive:${action.objectType}")
      .withFieldConst(_.label, label)
      .withFieldConst(_.data, data)
      .withFieldConst(_.tlp, tlp)
      .withFieldConst(_.pap, pap)
      .transform

  def getEntityLabel(entity: Entity): String = entity._model.label // FIXME
  /**
    * Executes an Action on user demand,
    * creates a job on Cortex side and then persist the
    * Action, looking forward job completion
    *
    * @param action the initial data
    * @param entity the Entity to execute an Action upon
    * @param authContext necessary auth context
    * @return
    */
  def execute(
      action: Action,
      entity: Entity
  )(implicit writes: OWrites[Entity], authContext: AuthContext): Future[RichAction] =
    for {
      client <- action.cortexId match {
        case Some(cortexId) =>
          cortexConfig
            .instances
            .get(cortexId)
            .fold[Future[CortexClient]](Future.failed(NotFoundError(s"Cortex $cortexId not found")))(Future.successful)
        case None if cortexConfig.instances.nonEmpty =>
          Future.firstCompletedOf {
            cortexConfig
              .instances
              .values
              .map(client => client.getResponder(action.responderId).map(_ => client))
          }
        case None => Future.failed(NotFoundError(s"Responder ${action.responderId} not found"))
      }
      (label, tlp, pap) <- Future.fromTry(db.tryTransaction(implicit graph => entityHelper.entityInfo(entity)))
      inputCortexAction = toCortexAction(action, label, tlp, pap, writes.writes(entity))
      job <- client.execute(action.responderId, inputCortexAction)
      updatedAction = action.copy(
        responderName = Some(job.workerName),
        responderDefinition = Some(job.workerDefinition),
        status = fromCortexJobStatus(job.status),
        startDate = job.startDate.getOrElse(new Date()),
        cortexId = Some(client.name),
        cortexJobId = Some(job.id)
      )
      createdAction <- Future.fromTry {
        db.tryTransaction { implicit graph =>
          create(updatedAction, entity)
        }
      }

      _ = cortexActor ! CheckJob(None, job.id, Some(createdAction._id), client, authContext)
    } yield createdAction

  /**
    * Creates an Action with necessary ActionContext edge
    *
    * @param action the action to persist
    * @param context the context Entity to link to
    * @param graph graph needed for db queries
    * @param authContext auth for db queries
    * @return
    */
  def create(
      action: Action,
      context: Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[RichAction] = {

    val createdAction = create(action)
    actionContextSrv.create(ActionContext(), createdAction, context)

    Success(RichAction(createdAction, context))
  }

  /**
    * Once the job is finished for a precise Action,
    * updates it
    *
    * @param actionId the action to update
    * @param cortexOutputJob the result Cortex job
    * @param authContext context for db queries
    * @return
    */
  def finished(actionId: String, cortexOutputJob: CortexOutputJob)(implicit authContext: AuthContext): Try[Action with Entity] =
    db.transaction { implicit graph =>
      val operations = {
        for {
          report <- cortexOutputJob.report.toSeq
          op     <- report.operations
          if op.status == ActionOperationStatus.Waiting
        } yield {
          for {
            action <- initSteps.get(actionId).richAction.getOrFail()
            operation <- actionOperationSrv.execute(
              action.context,
              op,
              relatedCase(actionId)
            )
          } yield operation
        }
      } flatMap (_.toOption)

      initSteps
        .get(actionId)
        .update(
          "status"     -> fromCortexJobStatus(cortexOutputJob.status),
          "report"     -> cortexOutputJob.report.map(r => Json.toJson(r.copy(operations = Nil))),
          "endDate"    -> new Date(),
          "operations" -> Json.toJson(operations).toString
        )
    }

  /**
    * Gets an optional related Case to the Action Entity
    * @param id action id
    * @param graph db graph
    * @return
    */
  def relatedCase(id: String)(implicit graph: Graph): Option[Case with Entity] =
    for {
      richAction  <- initSteps.get(id).richAction.getOrFail().toOption
      relatedCase <- entityHelper.parentCase(richAction.context)
    } yield relatedCase

  // TODO to be tested
  def listForEntity(id: String)(implicit graph: Graph): List[Action with Entity] = initSteps.forEntity(id).toList
}

@EntitySteps[Action]
class ActionSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph, schema: Schema) extends BaseVertexSteps[Action, ActionSteps](raw) {

  /**
    * Provides a RichAction model with additional Entity context
    *
    * @return
    */
  def richAction: ScalarSteps[RichAction] =
    ScalarSteps(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[ActionContext]))
        )
        .map {
          case (action, context) =>
            RichAction(action.as[Action], context.asEntity)
        }
    )

  def forEntity(entityId: String): ActionSteps =
    newInstance(
      raw.filter(
        _.outTo[ActionContext]
          .hasId(entityId)
      )
    )

  override def newInstance(raw: GremlinScala[Vertex]): ActionSteps = new ActionSteps(raw)
}
