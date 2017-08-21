package korolev.internal

import korolev._
import ApplicationContext._
import Async._
import levsha.{Id, StatefulRenderContext, XmlNs}
import levsha.events.EventId
import slogging.LazyLogging

import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

/**
  * Component state holder and effects performer
  *
  * Performing cycle:
  *
  * 1. [[prepare()]]
  * 2. Optionally [[setState()]]
  * 3. [[applyRenderContext()]]
  * 4. [[dropObsoleteMisc()]]
  */
final class ComponentInstance[F[+ _]: Async, AS, M, CS, P, E](nodeId: Id,
                                                              frontend: ClientSideApi[F],
                                                              eventRegistry: EventRegistry[F],
                                                              val component: Component[F, CS, P, E])
  extends LazyLogging {

  private val async = Async[F]
  private val miscLock = new Object()
  private val stateLock = new Object()
  private val subscriptionsLock = new Object()

  private val markedDelays = mutable.Set.empty[Id] // Set of the delays which are should survive
  private val markedComponentInstances = mutable.Set.empty[Id]
  private val delays = mutable.Map.empty[Id, Delay[F, CS, E]]
  private val elements = mutable.Map.empty[ElementId[F, CS, E], Id]
  private val events = mutable.Map.empty[EventId, Event[F, CS, E]]
  private val nestedComponents = mutable.Map.empty[Id, ComponentInstance[F, CS, E, _, _, _]]
  private val formDataPromises = mutable.Map.empty[String, Promise[F, FormData]]
  private val formDataProgressTransitions = mutable.Map.empty[String, (Int, Int) => Transition[CS]]
  private var state = component.initialState

  private val stateChangeSubscribers = mutable.ArrayBuffer.empty[(Id, Any) => Unit]
  private var eventSubscription = Option.empty[E => _]

  private object browserAccess extends Access[F, CS, E] {

    private def noElementException[T]: F[T] = {
      val exception = new Exception("No element matched for accessor")
      async.fromTry(Failure(exception))
    }

    private def getId(elementId: ElementId[F, CS, E]): F[Id] =
      elements
        .get(elementId)
        .fold(noElementException[Id])(id => async.pure(id))

    def property[T](elementId: ElementId[F, CS, E]): PropertyHandler[F, T] = {
      val idF = getId(elementId)
      new PropertyHandler[F, T] {
        def get(propName: Symbol): F[T] = idF flatMap { id =>
          // TODO get should be F[String]
          frontend.extractProperty(id, propName.name).asInstanceOf[F[T]]
        }

        def set(propName: Symbol, value: T): F[Unit] = idF flatMap { id =>
          // XmlNs argument is empty cause it will be ignored
          frontend.setProperty(id, propName, value)
          async.unit
        }
      }
    }

    def property[T](element: ElementId[F, CS, E], propName: Symbol): F[T] =
      property[T](element).get(propName)

    def focus(element: ElementId[F, CS, E]): F[Unit] =
      getId(element).flatMap { id =>
        frontend.focus(id)
        async.unit
      }

    def publish(message: E): F[Unit] = {
      eventSubscription.foreach(f => f(message))
      async.unit
    }

    def downloadFormData(element: ElementId[F, CS, E]): FormDataDownloader[F, CS] = new FormDataDownloader[F, CS] {
      val descriptor = Random.alphanumeric.take(5).mkString

      def start(): F[FormData] = getId(element) flatMap { id =>
        val promise = async.promise[FormData]
        frontend.uploadForm(id, descriptor)
        formDataPromises.put(descriptor, promise)
        promise.future
      }

      def onProgress(f: (Int, Int) => Transition[CS]): this.type = {
        formDataProgressTransitions.put(descriptor, f)
        this
      }
    }
  }

  private def applyEventResult(er: EventResult[F, CS]): Boolean = {
    // Apply immediate transition
    er.it match {
      case None => ()
      case Some(transition) =>
        applyTransition(transition)
    }
    // Apply deferred transition
    er.dt.fold(async.unit) { transitionF =>
      transitionF.map { transition =>
        applyTransition(transition)
      }
    } run {
      case Success(_) =>
      // ok transitions was applied
      case Failure(e) =>
        logger.error("Exception during applying transition", e)
    }
    !er.sp
  }

  private def createUnsubscribe[T](from: mutable.Buffer[T], that: T) = { () =>
    subscriptionsLock.synchronized { from -= that; () }
  }

  /**
    * Subscribe to component instance state changes.
    * Callback will be invoked for every state change.
    */
  def subscribeStateChange(callback: (Id, Any) => Unit): () => Unit = {
    subscriptionsLock.synchronized {
      stateChangeSubscribers += callback
      createUnsubscribe(stateChangeSubscribers, callback)
    }
  }

  /**
    * Subscribes to component instance events.
    * Callback will be invoked on call of [[Access.publish()]] in the
    * component instance context.
    */
  def setEventsSubscription(callback: E => _): Unit = {
    subscriptionsLock.synchronized {
      eventSubscription = Some(callback)
    }
  }

  /**
    * Set state of the component from the outside.
    */
  def setState(newState: CS): Unit = stateLock.synchronized {
    state = newState
  }

  /**
    * Type-unsafe version of setState
    */
  def setStateUnsafe(newState: Any): Unit = {
    setState(newState.asInstanceOf[CS])
  }

  def restoreState(stateReader: StateReader): Unit = {
    stateReader.read[CS](nodeId) foreach { newState =>
      setState(newState)
    }
  }

  /**
    * Gives current state of the component instance
    */
  def getState: CS = state

  def applyRenderContext(parameters: P,
                         rc: StatefulRenderContext[Effect[F, AS, M]],
                         stateReaderOpt: Option[StateReader]): Unit = miscLock.synchronized {

    val node = component.render(parameters, state)
    val proxy = new StatefulRenderContext[Effect[F, CS, E]] { proxy =>
      def currentId: Id = rc.currentId
      def currentContainerId: Id = rc.currentContainerId
      def openNode(xmlNs: XmlNs, name: String): Unit = rc.openNode(xmlNs, name)
      def closeNode(name: String): Unit = rc.closeNode(name)
      def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = rc.setAttr(xmlNs, name, value)
      def addTextNode(text: String): Unit = rc.addTextNode(text)
      def addMisc(misc: Effect[F, CS, E]): Unit = {
        misc match {
          case event: Event[F, CS, E] =>
            val id = rc.currentContainerId
            events.put(EventId(id, event.`type`.name, event.phase), event)
            eventRegistry.registerEventType(event.`type`)
          case element: ElementId[F, CS, E] =>
            val id = rc.currentContainerId
            elements.put(element, id)
            ()
          case delay: Delay[F, CS, E] =>
            val id = rc.currentContainerId
            markedDelays += id
            if (!delays.contains(id)) {
              delays.put(id, delay)
              delay.start(applyTransition)
            }
          case entry @ ComponentEntry(_, _: Any, _: (Any => EventResult[F, CS])) =>
            val id = rc.currentId
            nestedComponents.get(id) match {
              case Some(n: ComponentInstance[F, CS, E, Any, Any, Any]) if n.component.id == entry.component.id =>
                // Use nested component instance
                markedComponentInstances += id
                n.setEventsSubscription((e: Any) => applyEventResult(entry.eventHandler(e)))
                n.applyRenderContext(entry.parameters, proxy, stateReaderOpt)
              case _ =>
                // Create new nested component instance
                val n = entry.createInstance(id, frontend, eventRegistry)
                markedComponentInstances += id
                nestedComponents.put(id, n)
                stateReaderOpt.foreach { stateReader =>
                  // If state reader is available then try to
                  // to restore state for this nested component instance
                  n.restoreState(stateReader)
                }
                n.subscribeStateChange { (id, state) =>
                  // Propagate nested component instance state change event
                  // to high-level component instance
                  stateChangeSubscribers.foreach { f =>
                    f(id, state)
                  }
                }
                n.setEventsSubscription((e: Any) => applyEventResult(entry.eventHandler(e)))
                n.applyRenderContext(entry.parameters, proxy, stateReaderOpt)
            }
        }
      }
    }
    node(proxy)
  }

  def applyTransition(transition: Transition[CS]): Unit = {
    stateLock.synchronized {
      transition.lift(state) match {
        case Some(newState) => state = newState
        case None           => ()
      }
    }
    stateChangeSubscribers.foreach { f =>
      f(nodeId, state)
    }
  }

  def applyEvent(eventId: EventId): Boolean = {
    events.get(eventId) match {
      case Some(event: EventWithAccess[F, CS, E]) => applyEventResult(event.effect(browserAccess))
      case Some(event: SimpleEvent[F, CS, E])     => applyEventResult(event.effect())
      case None =>
        nestedComponents.values.forall { nested =>
          nested.applyEvent(eventId)
        }
    }
  }

  /**
    * Remove all delays and nested component instances
    * which were not marked during applying render context.
    */
  def dropObsoleteMisc(): Unit = miscLock.synchronized {
    delays foreach {
      case (id, delay) =>
        if (!markedDelays.contains(id)) {
          delays.remove(id)
          delay.cancel()
        }
    }
    nestedComponents foreach {
      case (id, nested) =>
        if (!markedComponentInstances.contains(id)) nestedComponents.remove(id)
        else nested.dropObsoleteMisc()
    }
  }

  /**
    * Prepares component instance to applying render context.
    * Removes all temporary and obsolete misc.
    * All nested components also will be prepared.
    */
  def prepare(): Unit = miscLock.synchronized {
    markedComponentInstances.clear()
    markedDelays.clear()
    elements.clear()
    events.clear()
    // Remove only finished delays
    delays foreach {
      case (id, delay) =>
        if (delay.isFinished)
          delays.remove(id)
    }
    nestedComponents.values.foreach { nested =>
      nested.prepare()
    }
  }

  def resolveFormData(descriptor: String, formData: Try[FormData]): Unit = miscLock.synchronized {
    formDataPromises.get(descriptor) match {
      case Some(promise) =>
        promise.complete(formData)
        // Remove promise and onProgress handler
        // when formData loading is complete
        formDataProgressTransitions.remove(descriptor)
        formDataPromises.remove(descriptor)
        ()
      case None =>
        nestedComponents.values.foreach { nested =>
          nested.resolveFormData(descriptor, formData)
        }
    }
  }

  def handleFormDataProgress(descriptor: String, loaded: Int, total: Int): Unit = miscLock.synchronized {
    formDataProgressTransitions.get(descriptor) match {
      case None =>
        nestedComponents.values.foreach { nested =>
          nested.handleFormDataProgress(descriptor, loaded, total)
        }
      case Some(f) => applyTransition(f(loaded.toInt, total.toInt))
    }
  }
}
