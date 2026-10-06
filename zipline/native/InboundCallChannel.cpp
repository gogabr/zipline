/*
 * Copyright (C) 2019 Square, Inc.
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
#include "InboundCallChannel.h"

#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

namespace {

jsi::Value invokeServiceMethod(
    ContextBase* context,
    const std::string& serviceName,
    const std::string& methodName,
    const std::string& cppArg) {
  jsi::Runtime& rt = context->getRuntime();

  jsi::Value global = rt.global();
  jsi::Value service = global.asObject(rt).getProperty(rt, serviceName.c_str());
  if (!service.isObject()) {
    context->throwJsException("JavaScript global called " + serviceName + " is missing or not an object");
    return jsi::Value::undefined();
  }

  jsi::Object serviceObj = service.asObject(rt);
  jsi::Value method = serviceObj.getProperty(rt, methodName.c_str());
  if (!method.isObject() || !method.asObject(rt).isFunction(rt)) {
    context->throwJsException("JavaScript global called " + serviceName + " has no function " + methodName);
    return jsi::Value::undefined();
  }

  jsi::Value jsArg = context->toJsString(cppArg);

  return method.asObject(rt).asFunction(rt).callWithThis(
      static_cast<jsi::IRuntime&>(rt),
      serviceObj,
      jsArg);
}

}  // namespace

InboundCallChannel::InboundCallChannel(std::string name) : name_(std::move(name)) {}

std::string InboundCallChannel::call(ContextBase* context, const std::string& callJson) const {
  // A platform exception thrown from inside guest code is already pending (routine on the
  // direct-event path); calling into the platform again would abort the VM.
  if (context->hasPendingPlatformException()) return "";
  jsi::Value result;
  try {
    result = invokeServiceMethod(context, name_, "call", callJson);
  } catch (const jsi::JSError& e) {
    context->throwJsError(const_cast<jsi::JSError&>(e));
    return "";
  }
  if (!result.isString()) {
    context->throwJsException("InboundCallChannel.call result was not a string");
    return "";
  }
  return context->toCppString(result.asString(context->getRuntime()));
}

bool InboundCallChannel::disconnect(ContextBase* context, const std::string& instanceName) const {
  if (context->hasPendingPlatformException()) return false;
  jsi::Value result;
  try {
    result = invokeServiceMethod(context, name_, "disconnect", instanceName);
  } catch (const jsi::JSError& e) {
    context->throwJsError(const_cast<jsi::JSError&>(e));
    return false;
  }
  if (!result.isBool()) {
    context->throwJsException("InboundCallChannel.disconnect result was not a boolean");
    return false;
  }
  return result.asBool();
}
