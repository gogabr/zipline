#ifndef ZIPLINE_CONTEXT_BASE_H
#define ZIPLINE_CONTEXT_BASE_H

#include <hermes/hermes.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

class InboundCallChannel;
class OutboundCallChannel;

namespace zipline_cdp {
struct Session;
}

// The engine context, shared by all platform layers (JNI, Kotlin/Native).
// It owns the Hermes runtime and the last-error string used by the C API,
// and provides the virtual surface the shared call channels
// (InboundCallChannel/OutboundCallChannel) work against. Platform layers
// inherit from it to attach their own per-runtime state and to customize
// error reporting (see ContextJni, ContextNative); such layers must use
// HermesCore_initContext/HermesCore_releaseContext instead of
// createContext/destroyContext so the derived object is allocated and
// deleted with its own type.
//
// TODO: this header currently leaks Hermes C++ implementation details
// (<hermes/hermes.h>, std::unique_ptr member) into every consumer. If the
// consumer list grows beyond the two engine glue .cpps, hide the members
// behind a pimpl or a forward-declared base with a virtual destructor.
struct ContextBase {
  virtual ~ContextBase();

  virtual facebook::jsi::Runtime& getRuntime();
  virtual facebook::jsi::String toJsString(const std::string& str);
  virtual std::string toCppString(const facebook::jsi::String& str);

  // Non-throwing error contract: implementations record the error (default:
  // into lastError) instead of throwing. throwJsError is for JS errors
  // caught in a host function; platforms that can splice the JS stack into
  // the platform exception (see ContextJni) override it.
  virtual void throwJsException(const std::string& message);
  virtual void throwJsError(facebook::jsi::JSError& error);

  // True when a platform exception is already pending on this thread. The shared call channels
  // use it before they touch the platform again: a Java exception thrown from inside guest code
  // is routine on the direct-event path, and re-entering JNI with one pending aborts the VM.
  // Platforms without such an exception (Kotlin/Native) never report one.
  virtual bool hasPendingPlatformException();

  std::unique_ptr<facebook::hermes::HermesRuntime> runtime;
  std::string lastError;

  // When CDP debugging is enabled for this context, runtime compilations must
  // emit full debug info (line tables, scoping info, sourceMappingURL magic
  // comments) even when no source map buffer is supplied.
  bool debugCompilation = false;

  // Active CDP debug session, owned by this context (see CdpSession.cpp). Raw
  // pointer because Session is only defined in the CdpSession translation
  // unit. Guarded by cdpSessionMutex: CDP commands arrive on transport
  // threads while detach() (engine close) destroys the session on the JS
  // thread.
  std::mutex cdpSessionMutex;
  zipline_cdp::Session* cdpSession = nullptr;

 protected:
  // Channels created through this context; deleted with it.
  std::vector<InboundCallChannel*> inboundChannels;
  std::vector<OutboundCallChannel*> outboundChannels;
};

#endif  // ZIPLINE_CONTEXT_BASE_H
