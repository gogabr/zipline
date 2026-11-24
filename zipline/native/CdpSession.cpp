#include "CdpSession.h"

#include "ContextBase.h"

#if HERMES_ENABLE_DEBUGGER

#include <hermes/AsyncDebuggerAPI.h>
#include <hermes/cdp/CDPAgent.h>
#include <hermes/cdp/CDPDebugAPI.h>

#include <hermes/hermes.h>

#include <atomic>
#include <deque>
#include <mutex>
#include <memory>
#include <thread>

namespace zipline_cdp {

namespace cdp = facebook::hermes::cdp;
namespace debugger = facebook::hermes::debugger;

struct Session {
  std::unique_ptr<cdp::CDPDebugAPI> debugApi;

  // Guarded by agentMutex: handleCommand may run on any transport thread
  // while resetAgent swaps the agent.
  std::mutex agentMutex;
  std::unique_ptr<cdp::CDPAgent> agent;

  Listener listener;

  ContextBase* ctx = nullptr;

  std::mutex queueMutex;
  std::deque<debugger::RuntimeTask> taskQueue;

  // The thread the runtime is registered on with Hermes' sampling profiler. The CDP Profiler
  // domain only gets samples from a registered runtime, and the sampler samples the thread that
  // registered it; that must be the JS thread, which isn't always the one that created the runtime.
  // Only touched on the JS thread, in drainTasks().
  std::thread::id profilingThread;

  // Whether the debugger is currently paused (tracked from CDP events).
  // While paused the JS thread is blocked in the debugger loop, so integrator
  // tasks must be serviced via AsyncDebuggerAPI interrupts, not the queue.
  std::atomic<bool> paused{false};
};

namespace {

void sendMessage(Session* session, const std::string& json) {
  // Track pause state from CDP events so queued runtime tasks can be serviced
  // through the debugger's interrupt path while the JS thread is paused.
  if (json.find("\"Debugger.paused\"") != std::string::npos) {
    session->paused.store(true);
  } else if (json.find("\"Debugger.resumed\"") != std::string::npos) {
    session->paused.store(false);
  }
  session->listener.onMessage(session->listener.ref, json);
}

// The agent may invoke these callbacks from arbitrary threads (including
// during ~CDPAgent), so they capture the raw session pointer; the session
// outlives the agent because detach() resets the agent first.
std::unique_ptr<cdp::CDPAgent> createAgent(Session* session, cdp::State state) {
  return cdp::CDPAgent::create(
      /*executionContextID=*/1,
      *session->debugApi,
      /*enqueueRuntimeTaskCallback=*/
      [session](debugger::RuntimeTask task) {
        {
          std::lock_guard<std::mutex> lock(session->queueMutex);
          session->taskQueue.push_back(std::move(task));
        }
        if (session->paused.load()) {
          // The JS thread is blocked in the debugger pause loop, so the
          // normal drain (posted to the Zipline dispatcher) can't run. Service
          // the queue through the debugger's interrupt path instead.
          session->debugApi->asyncDebuggerAPI().triggerInterrupt_TS(
              [session](facebook::hermes::HermesRuntime&) {
                drainTasks(session->ctx);
              });
        } else {
          session->listener.onTasksEnqueued(session->listener.ref);
        }
      },
      /*messageCallback=*/
      [session](const std::string& json) { sendMessage(session, json); },
      std::move(state));
}

}  // namespace

bool attach(ContextBase* ctx, Listener listener) {
  if (!ctx || !listener.ref || !listener.onMessage ||
      !listener.onTasksEnqueued || !listener.dispose) {
    if (listener.dispose) listener.dispose(listener.ref);
    return false;
  }
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (ctx->cdpSession) {
    listener.dispose(listener.ref);
    return true;
  }

  std::unique_ptr<Session> session(new Session());
  session->debugApi = cdp::CDPDebugAPI::create(*ctx->runtime);
  if (!session->debugApi) {
    listener.dispose(listener.ref);
    return false;
  }
  session->ctx = ctx;
  session->listener = listener;
  session->agent = createAgent(session.get(), {});

  ctx->cdpSession = session.release();
  return true;
}

void handleCommand(ContextBase* ctx, const std::string& json) {
  if (!ctx) return;
  // Held for the whole call: detach() must not free the session mid-command.
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (!ctx->cdpSession) return;
  Session* session = ctx->cdpSession;
  std::lock_guard<std::mutex> lock(session->agentMutex);
  if (session->agent) {
    session->agent->handleCommand(json);
  }
}

void resetAgent(ContextBase* ctx) {
  if (!ctx) return;
  // Held for the whole call: detach() must not free the session mid-swap.
  std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
  if (!ctx->cdpSession) return;
  Session* session = ctx->cdpSession;
  std::lock_guard<std::mutex> lock(session->agentMutex);
  if (!session->agent) return;
  {
    // Drop tasks enqueued by the old agent: they capture the old agent and
    // would run against it (use-after-free) if drained after the swap. The
    // client disconnected, so nothing is waiting on their results. Tasks the
    // old agent's destructor enqueues during the swap below (e.g. domain
    // dispose, which clears breakpoints) are safe — they keep their targets
    // alive — and must survive.
    std::lock_guard<std::mutex> queueLock(session->queueMutex);
    session->taskQueue.clear();
  }
  cdp::State state = session->agent->getState();
  session->agent = createAgent(session, std::move(state));
}

void drainTasks(ContextBase* ctx) {
  if (!ctx) return;
  for (;;) {
    debugger::RuntimeTask task;
    {
      // Only the queue pop is locked: detach() runs on this same (JS) thread,
      // so the session cannot be freed while the task executes.
      std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
      if (!ctx->cdpSession) return;
      Session* session = ctx->cdpSession;
      {
        std::lock_guard<std::mutex> lock(session->queueMutex);
        if (session->taskQueue.empty()) return;
        task = std::move(session->taskQueue.front());
        session->taskQueue.pop_front();
      }
      // Profiler.start runs as one of these tasks; bind the sampler to this (JS) thread first.
      // Registering again only re-binds the thread.
      if (session->profilingThread != std::this_thread::get_id()) {
        ctx->runtime->registerForProfiling();
        session->profilingThread = std::this_thread::get_id();
      }
    }
    // RuntimeTaskRunner wraps tasks so a task already executed via the
    // AsyncDebuggerAPI interrupt path is a no-op here (and vice versa).
    task(*ctx->runtime);
  }
}

void detach(ContextBase* ctx) {
  if (!ctx) return;
  Session* session;
  {
    std::lock_guard<std::mutex> sessionLock(ctx->cdpSessionMutex);
    session = ctx->cdpSession;
    if (!session) return;
    ctx->cdpSession = nullptr;
  }

  // The agent must go first: it uses the debug API and may still invoke our
  // callbacks (which reference the session) while being destroyed.
  {
    std::lock_guard<std::mutex> lock(session->agentMutex);
    session->agent.reset();
  }
  session->debugApi.reset();

  session->listener.dispose(session->listener.ref);
  delete session;
}

}  // namespace zipline_cdp

#else  // !HERMES_ENABLE_DEBUGGER

namespace zipline_cdp {

bool attach(ContextBase*, Listener listener) {
  if (listener.dispose) listener.dispose(listener.ref);
  return false;
}
void handleCommand(ContextBase*, const std::string&) {}
void resetAgent(ContextBase*) {}
void drainTasks(ContextBase*) {}
void detach(ContextBase*) {}

}  // namespace zipline_cdp

#endif  // HERMES_ENABLE_DEBUGGER
