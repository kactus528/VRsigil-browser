/* -*- Mode: C++; tab-width: 20; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef VRBROWSER_SIGIL_H
#define VRBROWSER_SIGIL_H

#include "vrb/Forward.h"
#include "vrb/MacroUtils.h"

#include <memory>
#include <string>

namespace crow {

class Sigil;
typedef std::shared_ptr<Sigil> SigilPtr;

// A square image shown on black in front of the viewer, replacing a 360/180 video
// while the sigil trigger (A+B on the right controller) is active.
class Sigil {
public:
  static SigilPtr Create(vrb::CreationContextPtr aContext);
  // aPath: absolute path to a PNG/JPG. aSizeDegrees: how much of the view it fills.
  void Load(const std::string& aPath, const float aSizeDegrees);
  bool IsLoaded() const;
  // Pin the sigil in front of the head, where it stays until the next Place().
  void Place(const vrb::Matrix& aHeadTransform);
  vrb::NodePtr GetRoot() const;
protected:
  struct State;
  Sigil(State& aState, vrb::CreationContextPtr& aContext);
  ~Sigil() = default;
private:
  State& m;
  Sigil() = delete;
  VRB_NO_DEFAULTS(Sigil)
};

} // namespace crow

#endif // VRBROWSER_SIGIL_H
