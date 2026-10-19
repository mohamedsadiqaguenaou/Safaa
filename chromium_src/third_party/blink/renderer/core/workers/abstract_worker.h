/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_ABSTRACT_WORKER_H_
#define BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_ABSTRACT_WORKER_H_

#include "third_party/blink/renderer/core/core_export.h"
#include "third_party/blink/renderer/core/dom/events/event_listener.h"
#include "third_party/blink/renderer/core/dom/events/event_target.h"
#include "third_party/blink/renderer/core/execution_context/execution_context_lifecycle_state_observer.h"
#include "third_party/blink/renderer/platform/heap/garbage_collected.h"
#include "third_party/blink/renderer/platform/wtf/forward.h"

// Workers started by an isolated world that runs without CSP (an empty
// isolated-world CSP, like Brave's internal world) are not checked against the
// page's CSP either. See abstract_worker.cc.
#define CheckAllowedByCSPForNoThrow                                  \
  CheckAllowedByCSPForNoThrow_ChromiumImpl(const KURL& script_url); \
  bool CheckAllowedByCSPForNoThrow

#include <third_party/blink/renderer/core/workers/abstract_worker.h>  // IWYU pragma: export

#undef CheckAllowedByCSPForNoThrow

#endif  // BRAVE_CHROMIUM_SRC_THIRD_PARTY_BLINK_RENDERER_CORE_WORKERS_ABSTRACT_WORKER_H_
