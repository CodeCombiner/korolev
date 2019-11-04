/*
 * Copyright 2017-2018 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package korolev

import Extension.Handlers

trait Extension[F[_], S, M] {
  def setup(access: Context.BaseAccess[F, S, M]): F[Handlers[F, S, M]]
}

object Extension {

  abstract class Handlers[F[_]: Async, S, M] {
    def onState(state: S): F[Unit] = Async[F].unit
    def onMessage(message: M): F[Unit] = Async[F].unit
    def onDestroy(): F[Unit] = Async[F].unit
  }

  private final class HandlersImpl[F[_]: Async, S, M](_onState: S => F[Unit],
                                                      _onMessage: M => F[Unit],
                                                      _onDestroy: () => F[Unit]) extends Handlers[F, S, M] {
    override def onState(state: S): F[Unit] = _onState(state)
    override def onMessage(message: M): F[Unit] = _onMessage(message)
    override def onDestroy(): F[Unit] = _onDestroy()
  }

  object Handlers {
    def apply[F[_]: Async, S, M](onState: S => F[Unit] = null,
                                 onMessage: M => F[Unit] = null,
                                 onDestroy: () => F[Unit] = null): Handlers[F, S, M] =
      new HandlersImpl(
        if (onState == null) _ => Async[F].unit else onState,
        if (onMessage == null) _ => Async[F].unit else onMessage,
        if (onDestroy == null) () => Async[F].unit else onDestroy
      )
  }

  def apply[F[_], S, M](f: Context.BaseAccess[F, S, M] => F[Handlers[F, S, M]]): Extension[F, S, M] =
    (access: Context.BaseAccess[F, S, M]) => f(access)

  def pure[F[_]: Async, S, M](f: Context.BaseAccess[F, S, M] => Handlers[F, S, M]): Extension[F, S, M] =
    (access: Context.BaseAccess[F, S, M]) => Async[F].pure(f(access))
}
