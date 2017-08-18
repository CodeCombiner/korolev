package object korolev {

  // Routing API
  @inline val Root = Router.Root
  @inline val / = Router./
  type / = Router./

  @deprecated("Use ApplicationContext instead of Effects", "0.4.0")
  val Effects = ApplicationContext

  type Transition[State] = PartialFunction[State, State]

  object StateManager {
    @deprecated("Use korolev.Transition", "0.6.0")
    type Transition[State] = PartialFunction[State, State]
  }
}
