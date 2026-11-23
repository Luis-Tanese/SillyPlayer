#pragma once
#include "player.h"
#include <QQuickFramebufferObject>
class VideoItem : public QQuickFramebufferObject {
  Q_OBJECT
  Q_PROPERTY(Player *player READ player WRITE setPlayer NOTIFY playerChanged)
public:
  VideoItem() {
    setMirrorVertically(false);
    connect(this, &QQuickItem::visibleChanged, this, [this] {
      if (isVisible())
        update();
    });
  }
  Player *player() const { return p; }
  void setPlayer(Player *value) {
    p = value;
    emit playerChanged();
    update();
  }
  Renderer *createRenderer() const override;
signals:
  void playerChanged();

private:
  Player *p = nullptr;
};
