#include "ContextBase.h"

#include "InboundCallChannel.h"
#include "OutboundCallChannel.h"

namespace jsi = facebook::jsi;

ContextBase::~ContextBase() {
  for (auto* ch : inboundChannels) delete ch;
  for (auto* ch : outboundChannels) delete ch;
}

jsi::Runtime& ContextBase::getRuntime() {
  return *runtime;
}

jsi::String ContextBase::toJsString(const std::string& str) {
  return jsi::String::createFromUtf8(*runtime, str);
}

std::string ContextBase::toCppString(const jsi::String& str) {
  return str.utf8(*runtime);
}

void ContextBase::throwJsException(const std::string& message) {
  lastError = message;
}

void ContextBase::throwJsError(jsi::JSError& error) {
  throwJsException(error.getMessage());
}

bool ContextBase::hasPendingPlatformException() {
  return false;
}
