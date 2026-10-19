/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_WORKLET_H_
#define BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_WORKLET_H_

#include "third_party/blink/renderer/bindings/core/v8/script_promise.h"
#include "third_party/blink/renderer/bindings/core/v8/v8_request_credentials.h"
#include "third_party/blink/renderer/core/core_export.h"
#include "third_party/blink/renderer/core/execution_context/execution_context_lifecycle_observer.h"
#include "third_party/blink/renderer/core/workers/worklet_global_scope_proxy.h"
#include "third_party/blink/renderer/core/workers/worklet_module_responses_map.h"
#include "third_party/blink/renderer/core/workers/worklet_pending_tasks.h"
#include "third_party/blink/renderer/platform/bindings/script_wrappable.h"
#include "third_party/blink/renderer/platform/heap/collection_support/heap_hash_set.h"
#include "third_party/blink/renderer/platform/heap/garbage_collected.h"
#include "third_party/blink/renderer/platform/heap/prefinalizer.h"

// Worklets started by an isolated world that runs without CSP (an empty
// isolated-world CSP, like Brave's internal world) are not subject to the
// page's CSP either. See worklet.cc.
#define addModule                                                 \
  addModule_ChromiumImpl(ScriptState*, const String& module_url,  \
                         const WorkletOptions*, ExceptionState&); \
  ScriptPromise<IDLUndefined> addModule

#include <third_party/blink/renderer/core/workers/worklet.h>  // IWYU pragma: export

#undef addModule

#endif  // BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_WORKLET_H_
