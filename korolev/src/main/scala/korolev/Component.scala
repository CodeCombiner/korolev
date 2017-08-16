package korolev

import korolev.ApplicationContext._
import korolev.Async._
import korolev.util.Scheduler
import levsha.Document.Node
import levsha.events.EventId
import levsha.{Id, StatefulRenderContext, XmlNs}

import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

/**
  * Component definition. Every Korolev application is a component.
  * Extent it to declare component in object oriented style.
  *
  * @tparam F Control monad
  * @tparam S State of the component
  * @tparam E Type of events produced by component
  */
abstract class Component[F[+ _]: Async: Scheduler, S, E](val id: String = Random.alphanumeric.take(5).mkString) {

  /**
    * Component context.
    *
    * {{{
    *  import context._
    *  import symbolDsl._
    * }}}
    */
  val context = ApplicationContext[F, S, E]

  /**
    * Component render
    */
  def render(state: S): context.symbolDsl.Node
}

object Component {

  /** (context, state) => document */
  type Render[F[+ _], S, E] = (ApplicationContext[F, S, E], S) => Node[Effect[F, S, E]]

  /**
    * Create component in functional style
    * @param f Component renderer
    * @see [[Component]]
    */
  def apply[F[+ _]: Async: Scheduler, S, E](f: Render[F, S, E]): Component[F, S, E] =
    new ComponentFunction(f)

  /** A way to define components in functional style */
  private class ComponentFunction[F[+ _]: Async: Scheduler, S, E](f: Render[F, S, E]) extends Component[F, S, E] {

    def render(state: S): Node[Effect[F, S, E]] = f(context, state)
  }

  /**
    * Typed interface to client side
    */
  abstract class Frontend[F[+ _]: Async] {
    // client.call("ListenEvent", `type`.name, false)
    def listenEvent(name: String, preventDefault: Boolean): F[Unit]

    // client.call[Unit]("UploadForm", id.mkString, descriptor)
    def uploadForm(id: Id, descriptor: String): F[Unit]

    // client.callAndFlush[Unit]("Focus", id.mkString)
    def focus(id: Id): F[Unit]

    // client.callAndFlush("SetAttr", id.mkString, "", propName.name, value, true)
    def setAttr[T](id: Id, xmlNs: String, name: String, value: T, isProperty: Boolean): F[Unit]

    // client.callAndFlush("ExtractProperty", id.mkString, propName.name)
    def extractProperty[T](id: Id, name: String): F[T]
  }

  /**
    * Save information about what type of events are already
    * listening on the client
    */
  final class EventRegistry[F[+ _]: Async](frontend: Frontend[F]) {

    private val knownEventTypes = mutable.Set('submit)

    /**
      * Notifies client side that he should listen
      * all events of the type. If event already listening
      * on the client side, client will be not notified again.
      */
    def registerEventType(`type`: Symbol): Unit = knownEventTypes.synchronized {
      if (!knownEventTypes.contains(`type`)) {
        knownEventTypes += `type`
        frontend.listenEvent(`type`.name, preventDefault = false).runIgnoreResult
      }
    }
  }

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
  final class ComponentInstance[F[+ _]: Async, AS, M, CS, E](initialState: CS,
                                                             frontend: Frontend[F],
                                                             eventRegistry: EventRegistry[F],
                                                             val component: Component[F, CS, E]) {

    private val async = Async[F]

    private val miscLock = new Object()
    private val stateLock = new Object()

    private val markedDelays = mutable.Set.empty[Id] // Set of the delays which are should survive
    private val markedComponentInstances = mutable.Set.empty[Id]
    private val delays = mutable.Map.empty[Id, Delay[F, CS, E]]
    private val elements = mutable.Map.empty[ElementId[F, CS, E], Id]
    private val events = mutable.Map.empty[EventId, Event[F, CS, E]]
    private val nestedComponents = mutable.Map.empty[Id, ComponentInstance[F, CS, E, _, _]]
    private val formDataPromises = mutable.Map.empty[String, Promise[F, FormData]]
    private val formDataProgressTransitions = mutable.Map.empty[String, (Int, Int) => Transition[CS]]
    private val stateChangeSubscribers = mutable.ArrayBuffer.empty[() => Unit]
    private val eventSubscribers = mutable.ArrayBuffer.empty[E => _]

    private var state = initialState
    private var lastSetState = initialState

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
            frontend.extractProperty(id, propName.name)
          }

          def set(propName: Symbol, value: T): F[Unit] = idF flatMap { id =>
            // XmlNs argument is empty cause it will be ignored
            frontend.setAttr(id, "", propName.name, value, isProperty = true)
          }
        }
      }

      def property[T](element: ElementId[F, CS, E], propName: Symbol): F[T] =
        property[T](element).get(propName)

      def focus(element: ElementId[F, CS, E]): F[Unit] =
        getId(element).flatMap(id => frontend.focus(id))

      def publish(message: E): F[Unit] = {
        eventSubscribers.foreach(_(message))
        async.unit
      }

      def downloadFormData(element: ElementId[F, CS, E]): FormDataDownloader[F, CS] = new FormDataDownloader[F, CS] {
        val descriptor = Random.alphanumeric.take(5).mkString

        def start(): F[FormData] = getId(element) flatMap { id =>
          val promise = async.promise[FormData]
          val future = frontend.uploadForm(id, descriptor)
          formDataPromises.put(descriptor, promise)
          future.flatMap(_ => promise.future)
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
        case Failure(_) =>
        // TODO log error
        //logger.error("Exception during applying transition", e)
      }
      !er.sp
    }

    private def createUnsubscribe[T](from: mutable.Buffer[T], that: T) = { () =>
      { from -= that; () }
    }

    /**
      * Subscribe to component instance state changes.
      * Callback will be invoked for every state change.
      */
    def subscribeStateChange(callback: () => Unit): () => Unit = {
      stateChangeSubscribers += callback
      createUnsubscribe(stateChangeSubscribers, callback)
    }

    /**
      * Subscribes to component instance events.
      * Callback will be invoked on call of [[Access.publish()]] in the
      * component instance context.
      */
    def subscribeEvents(callback: E => _): () => Unit = {
      eventSubscribers += callback
      createUnsubscribe(stateChangeSubscribers, callback)
    }

    /**
      * Set state of the component from the outside. For example
      * from a top level component. Component instance remember
      * last [[setState]] value and don't update current state if
      * new state and last setState value is equals.
      */
    def setState(newState: CS, force: Boolean = false): Unit = stateLock.synchronized {
      if (force) {
        state = newState
        lastSetState = newState
      } else {
        if (lastSetState != newState) {
          state = newState
          lastSetState = newState
        }
      }
    }

    /**
      * Type-unsafe version of setState
      */
    def setStateUnsafe(newState: Any): Unit = {
      setState(newState.asInstanceOf[CS])
    }

    /**
      * Gives current state of the component instance
      */
    def getState: CS = state

    /**
      * TODO doc
      */
    def applyRenderContext(rc: StatefulRenderContext[Effect[F, AS, M]]): Unit = miscLock.synchronized {
      val node = component.render(state)
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
            case entry @ ComponentEntry(value, newState, eventHandler: (Any => EventResult[F, CS])) =>
              // TODO Event handler was changed but instance not. May be eventHandler should be mutable
              val id = rc.currentId
              nestedComponents.get(id) match {
                case Some(nested) if nested.component.id == value.id =>
                  markedComponentInstances += id
                  nested.setStateUnsafe(newState)
                  nested.applyRenderContext(proxy)
                case _ =>
                  val nested = entry.createInstance(frontend, eventRegistry)
                  markedComponentInstances += id
                  nestedComponents.put(id, nested)
                  nested.subscribeStateChange(() => stateChangeSubscribers.foreach(_()))
                  nested.subscribeEvents((e: Any) => applyEventResult(eventHandler(e)))
                  nested.applyRenderContext(proxy)
              }
          }
        }
      }
      node(proxy)
    }

    def applyTransition(transition: Transition[CS]): Unit = stateLock.synchronized {
      transition.lift(state) match {
        case Some(newState) => state = newState
        case None           => ()
      }
      stateChangeSubscribers.foreach(_())
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
          if (delay.finished)
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

  final val TopLevelComponentId = "top-level"
}
