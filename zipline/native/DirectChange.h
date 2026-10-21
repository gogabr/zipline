#ifndef ZIPLINE_DIRECT_CHANGE_H
#define ZIPLINE_DIRECT_CHANGE_H

#include <jsi/jsi.h>

#include <memory>

// Direct Changes support, shared by the JNI (Android/JVM) and iOS engine
// layers. Both accumulate DirectChange entries from JS host functions and
// flush them to the platform sink (redwood-treehouse).
enum class DirectChangeType {
  Create,
  PropertyChange,
  ModifierChange,
  Add,
  Remove,
  Move,
  BridgeChange,
};

constexpr int DIRECT_BRIDGE_BATCH_SIZE = 2048;

struct DirectChange {
  DirectChangeType type;
  int id;
  int field1;    // tag (Create/Remove/Move), widgetTag (PropertyChange), childrenTag (Add)
  int field2;    // propertyTag (PropertyChange), childId (Add), index (Remove), fromIndex (Move)
  int field3;    // index (Add), toIndex (Move)
  int count;     // count (Move only)
  bool detach;   // detach flag (Remove only)
  // jsi::Value has no copy ctor; we share ownership of the underlying JS
  // value so it stays alive until the change is flushed to the platform.
  std::shared_ptr<facebook::jsi::Value> jsValue;

  DirectChange() = default;
  DirectChange(const DirectChange&) = default;
  DirectChange& operator=(const DirectChange&) = default;
  DirectChange(DirectChange&&) = default;
  DirectChange& operator=(DirectChange&&) = default;
};

#endif  // ZIPLINE_DIRECT_CHANGE_H
