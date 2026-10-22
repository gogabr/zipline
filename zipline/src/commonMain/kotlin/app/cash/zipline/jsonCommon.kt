/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/*
 * Both the JS and JVM actuals are string-based (kotlinx `encodeToString`/`decodeFromString`). The JS
 * actual used to go through `encodeToDynamic`/`decodeFromDynamic` for a ~35% QuickJS speedup, but the
 * JS-object encoder turns every Kotlin `Long` into a double, silently rounding values outside
 * +/-2^53-1 in any nested position (lists, maps, DTO fields). String transport is exact for `Long`
 * everywhere, so correctness wins over that speedup.
 */

public expect fun <T> Json.decodeFromStringFast(deserializer: DeserializationStrategy<T>, string: String): T

public expect fun <T> Json.encodeToStringFast(serializer: KSerializer<T>, value: T): String
