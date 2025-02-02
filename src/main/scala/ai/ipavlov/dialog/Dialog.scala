package ai.ipavlov.dialog

import java.time.{Clock, Instant}

import ai.ipavlov.Implicits
import ai.ipavlov.communication.Endpoint
import ai.ipavlov.communication.user.{Bot, Human, Messages, UserSummary}
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.Try

/**
  * @author vadim
  * @since 06.07.17
  */
class Dialog(a: UserSummary, b: UserSummary, txtContext: String, gate: ActorRef, database: ActorRef, clck: Clock) extends Actor with ActorLogging with Implicits {
  import Dialog._

  private val faceA = Messages.randomUserSymbol
  private val faceB = Messages.randomUserSymbol

  private val idleTimers = mutable.Map[UserSummary, Deadline](a -> (Deadline.now + 1.minute), b -> (Deadline.now + 1.minute))

  private implicit val ec: ExecutionContextExecutor = context.dispatcher

  private val timeout = Try(Duration.fromNanos(context.system.settings.config.getDuration("talk.talk_timeout").toNanos)).getOrElse(10.minutes)
  private val maxLen = Try(context.system.settings.config.getInt("talk.talk_length_max")).getOrElse(1000)

  context.system.scheduler.schedule(1.minute, 1.minute) { self ! CheckIdleUsers }
  context.system.scheduler.scheduleOnce(timeout) { self ! EndDialog }

  private val history: mutable.LinkedHashMap[String, HistoryItem] = mutable.LinkedHashMap.empty[String, HistoryItem]

  log.info("start talk between {} and {}", a, b)

  @tailrec
  private def genId: String = {
    val id = Instant.now(clck).getNano.toString
    if (history.contains(id)) genId
    else id
  }

  override def receive: Receive = {
    case StartDialog =>
      def firstMessageFor(user: UserSummary, face: String, text: String): Endpoint.MessageFromDialog = user match {
        case u: Human => Endpoint.ShowInDialogSystemFlowup(u, text)
        case u: Bot => Endpoint.ShowChatMessageToUser(u, face, "/start " + text, self.chatId, genId)
      }

      gate ! firstMessageFor(a, faceA, txtContext)
      gate ! firstMessageFor(b, faceB, txtContext)

    case PushMessageToTalk(from, text) =>
      val (oppanent, face) = if (from == a) (b, faceB) else if (from == b) (a, faceA) else throw new IllegalArgumentException(s"$from not in talk")
      val id = genId
      gate ! Endpoint.ShowChatMessageToUser(oppanent, face, text, self.chatId, id)
      //TODO: use hash as id may leads to message lost!
      history.put(id, HistoryItem(from, text, 0, Instant.now(clck).getEpochSecond))
      if (history.size > maxLen) self ! EndDialog

      idleTimers.get(from).foreach(s => idleTimers += from -> (Deadline.now + 10.minutes))

    case EndDialog =>
      val e1 = context.actorOf(EvaluationProcess.props(a, self, gate), name=s"evaluation-process-${self.chatId}-${a.address}")
      e1 ! EvaluationProcess.StartEvaluation
      val e2 = context.actorOf(EvaluationProcess.props(b, self, gate), name=s"evaluation-process-${self.chatId}-${b.address}")
      e2 ! EvaluationProcess.StartEvaluation
      context.become(onEvaluation(e1, e2))

    case EvaluateMessage(messageId, category) =>
      history.get(messageId).fold {
        log.info("message {} not present in history", messageId)
      } { case HistoryItem(from, text, _, timestamp) =>
        history.update(messageId, HistoryItem(from, text, category, timestamp))
        log.info("rated message {} with {}", messageId, category)
      }

    case Complain(user) =>
      val e1 = context.actorOf(EvaluationProcess.props(a, self, gate), name=s"evaluation-process-${self.chatId}-${a.address}")
      e1 ! EvaluationProcess.StartEvaluation
      val e2 = context.actorOf(EvaluationProcess.props(b, self, gate), name=s"evaluation-process-${self.chatId}-${b.address}")
      e2 ! EvaluationProcess.StartEvaluation
      context.become(onEvaluation(e1, e2))

      val complainTo = if (a == user) b else a
      database ! MongoStorage.Complain(user, complainTo, self.chatId)
      log.info("user {} comlained to user {}, dialog id {}", user, complainTo, self.chatId)

    case CheckIdleUsers =>
      idleTimers.foreach {
        case (u: Human, deadline) if deadline.isOverdue() =>
          gate ! Endpoint.ShowInDialogSystemFlowup(u, Messages.partnerHangUp)
          idleTimers += (u -> (Deadline.now + 1.hour))
        case _ =>
      }
  }

  private val evaluations: mutable.Set[(UserSummary, (Int, Int, Int))] = mutable.Set.empty[(UserSummary, (Int, Int, Int))]

  def onEvaluation(aEvaluation: ActorRef, bEvaluation: ActorRef): Receive = {
    case EvaluationProcess.CompleteEvaluation(user, q, br, e) =>
      log.info("evaluation from {}: quality={}, breadth={}, engagement={}", user, q, br, e)
      evaluations.add(user -> (q, br, e))
      user match {
        case user: Human =>
          gate ! Endpoint.FinishTalkForUser(user, self)
          log.debug("human {} unavailable now", user)
        case user: Bot => log.debug("bot {} finished talk", user)
      }
      if (evaluations.size >= 2) {
        database ! MongoStorage.WriteDialog(self.chatId, Set(a, b), txtContext, history.values.toList, evaluations.toSet)
        self ! PoisonPill
      }

    case EndDialog => log.debug("already engagement")
    case m @ PushMessageToTalk(from, _) =>
      (if (from == a) aEvaluation else if (from == b) bEvaluation else throw new IllegalArgumentException(s"$from not in talk")) forward m

    case EvaluateMessage(messageId, category) =>
      history.get(messageId).fold {
        log.warning("message {} not present in history", messageId)
      } { case HistoryItem(from, text, _, timestamp) =>
        history.update(messageId, HistoryItem(from, text, category, timestamp))
        log.info("rated message {} with {}", messageId, category)
      }

    case CheckIdleUsers =>

    case m => log.debug("message ignored {}", m)
  }
}

object Dialog {
  def props(userA: UserSummary, userB: UserSummary, context: String, gate: ActorRef, database: ActorRef, clck: Clock) = Props(new Dialog(userA, userB, context, gate, database, clck))

  case class PushMessageToTalk(from: UserSummary, message: String)

  case object StartDialog
  case object EndDialog
  case class Complain(user: UserSummary)

  case class EvaluateMessage(messageId: String, category: Int)

  case class HistoryItem(summary: UserSummary, text: String, eval: Int, timestamp: Long)

  private case object CheckIdleUsers
}
