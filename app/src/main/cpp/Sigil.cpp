/* -*- Mode: C++; tab-width: 20; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "Sigil.h"
#include "Quad.h"
#include "vrb/ConcreteClass.h"
#include "vrb/CreationContext.h"
#include "vrb/Logger.h"
#include "vrb/Matrix.h"
#include "vrb/TextureGL.h"
#include "vrb/Toggle.h"
#include "vrb/Transform.h"
#include "vrb/Vector.h"

#include <cmath>
#include <fstream>

namespace crow {

// The sigil sits this far in front of the eyes. Far enough to focus comfortably,
// close enough to stay in front of anything else in the scene.
static const float kSigilDistance = 2.0f;

struct Sigil::State {
  vrb::CreationContextWeak context;
  vrb::TogglePtr root;
  QuadPtr quad;
  bool loaded = false;

  void Initialize() {
    vrb::CreationContextPtr create = context.lock();
    root = vrb::Toggle::Create(create);
  }
};

void
Sigil::Load(const std::string& aPath, const float aSizeDegrees) {
  vrb::CreationContextPtr create = m.context.lock();
  if (!create) {
    return;
  }
  if (m.quad) {
    m.root->RemoveNode(*m.quad->GetRoot());
    m.quad = nullptr;
  }
  m.loaded = false;
  if (aPath.empty() || !std::ifstream(aPath).good()) {
    VRB_LOG("Sigil: no image at '%s'", aPath.c_str());
    return;
  }

  // Don't use the texture cache: the file is replaced every time a new sigil is saved.
  vrb::TextureGLPtr texture = create->LoadTexture(aPath, false);
  if (!texture || texture->GetWidth() <= 0 || texture->GetHeight() <= 0) {
    VRB_ERROR("Sigil: failed to load '%s'", aPath.c_str());
    return;
  }
  texture->SetTextureParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  texture->SetTextureParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR);

  const float degrees = std::fmax(5.0f, std::fmin(aSizeDegrees, 100.0f));
  const float width = 2.0f * kSigilDistance * tanf(degrees * (float)M_PI / 360.0f);
  const float aspect = (float)texture->GetWidth() / (float)texture->GetHeight();
  m.quad = Quad::Create(create, width, width / aspect);
  m.quad->SetTexture(texture, texture->GetWidth(), texture->GetHeight());
  m.quad->UpdateProgram("");
  m.root->AddNode(m.quad->GetRoot());
  m.loaded = true;
  VRB_LOG("Sigil: loaded %dx%d from '%s'", texture->GetWidth(), texture->GetHeight(), aPath.c_str());
}

bool
Sigil::IsLoaded() const {
  return m.loaded;
}

void
Sigil::Place(const vrb::Matrix& aHeadTransform) {
  if (!m.quad) {
    return;
  }
  // Keep the head's yaw and pitch so the sigil is dead ahead, but drop roll so it's level.
  const vrb::Vector position = aHeadTransform.GetTranslation();
  vrb::Vector forward = aHeadTransform.MultiplyDirection(vrb::Vector(0.0f, 0.0f, -1.0f)).Normalize();
  const vrb::Vector worldUp(0.0f, 1.0f, 0.0f);
  vrb::Vector right = forward.Cross(worldUp);
  if (right.Magnitude() < 0.001f) {
    // Looking straight up or down: fall back to the head's own right vector.
    right = aHeadTransform.MultiplyDirection(vrb::Vector(1.0f, 0.0f, 0.0f));
  }
  right = right.Normalize();
  const vrb::Vector up = right.Cross(forward).Normalize();
  const vrb::Vector center = position + forward * kSigilDistance;

  // Columns: right, up, back (facing the viewer), position.
  vrb::Matrix transform(right.x(), right.y(), right.z(), 0.0f,
                        up.x(), up.y(), up.z(), 0.0f,
                        -forward.x(), -forward.y(), -forward.z(), 0.0f,
                        center.x(), center.y(), center.z(), 1.0f);
  m.quad->GetTransformNode()->SetTransform(transform);
}

vrb::NodePtr
Sigil::GetRoot() const {
  return m.root;
}

SigilPtr
Sigil::Create(vrb::CreationContextPtr aContext) {
  return std::make_shared<vrb::ConcreteClass<Sigil, Sigil::State> >(aContext);
}

Sigil::Sigil(State& aState, vrb::CreationContextPtr& aContext) : m(aState) {
  m.context = aContext;
  m.Initialize();
}

} // namespace crow
