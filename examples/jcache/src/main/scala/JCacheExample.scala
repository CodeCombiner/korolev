import javax.cache.configuration.MutableConfiguration

import com.hazelcast.cache.HazelcastCachingProvider

import korolev._
import korolev.server._
import korolev.akkahttp._
import korolev.execution._
import korolev.state.javaSerialization._
import korolev.state.cacheApiSupport.CachedStateStorage

import scala.concurrent.Future

object JCacheExample extends SimpleAkkaHttpKorolevApp {

  import State.globalContext._
  import State.globalContext.symbolDsl._

  val storage = {

    val cacheName = "default"
    val cachingProvider = new HazelcastCachingProvider()
    val cacheManager = cachingProvider.getCacheManager()
    val cache = Option(cacheManager.getCache(cacheName, classOf[String], classOf[Array[Byte]])) getOrElse {
      val config = new MutableConfiguration[String, Array[Byte]]()
      config.setTypes(classOf[String], classOf[Array[Byte]])
      cacheManager.createCache[String, Array[Byte], config.type](cacheName, config)
    }

    CachedStateStorage[Future, State] (cache) { _ =>
      Future.successful(State())
    }
  }

  val inputId = elementId()

  val service = akkaHttpService{
    KorolevServiceConfig [Future, State, Any] (
      stateStorage = storage,
      head = {
        Seq(
          'title("Main Routing Page"),
          'link(
            'href /= "/main.css",
            'rel /= "stylesheet",
            'type /= "text/css"
          )
        )
      },
      render = {
        case state =>
          'body(
            'div("Super TODO tracker"),
            'div(
              state.todos.keys map { name =>
                'span(
                  event('click) { access =>
                    access.transition { case s =>
                      s.copy(selectedTab = name)
                    }
                  },
                  'marginLeft @= 10,
                  if (name == state.selectedTab) 'strong(name)
                  else name
                )
              }
            ),
            'div('class /= "todos",
              (state.todos(state.selectedTab) zipWithIndex) map {
                case (todo, i) =>
                  'div(
                    'div(
                      'class /= {
                        if (!todo.done) "checkbox"
                        else "checkbox checkbox__checked"
                      },
                      // Generate transition when clicking checkboxes
                      event('click) { access =>
                        access.transition { case s =>
                          val todos = s.todos(s.selectedTab)
                          val updated = todos.updated(i, todos(i).copy(done = !todo.done))
                          s.copy(todos = s.todos + (s.selectedTab -> updated))
                        }
                      }
                    ),
                    if (!todo.done) 'span(todo.text)
                    else 'strike(todo.text)
                  )
              }
            ),
            'form(
              // Generate AddTodo action when 'Add' button clicked
              event('submit) { access =>
                access.property(inputId, 'value) flatMap { value =>
                  val todo = State.Todo(value, done = false)
                  access.transition { case s =>
                    s.copy(todos = s.todos + (s.selectedTab -> (s.todos(s.selectedTab) :+ todo)))
                  }
                }
              },
              'input(
                inputId,
                'type /= "text",
                'placeholder /= "What should be done?"
              ),
              'button("Add todo")
            )
          )
      },
      router = emptyRouter
    )
  }
}

case class State(
  selectedTab: String = "Tab1",
  todos: Map[String, Vector[State.Todo]] = Map(
    "Tab1" -> State.Todo(5),
    "Tab2" -> State.Todo(7),
    "Tab3" -> State.Todo(2)
  )
)

object State {
  val globalContext = Context[Future, State, Any]
  case class Todo(text: String, done: Boolean)
  object Todo {
    def apply(n: Int): Vector[Todo] = (0 to n).toVector map {
      i => Todo(s"This is TODO #$i", done = false)
    }
  }
}

