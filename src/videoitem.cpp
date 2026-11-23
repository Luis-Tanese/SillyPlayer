#include "videoitem.h"
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QPointer>
#include <QQuickOpenGLUtils>
#include <mpv/render_gl.h>

class VideoRenderer : public QQuickFramebufferObject::Renderer {
  mpv_render_context *context = nullptr;
  mpv_handle *handle = nullptr;
  QPointer<VideoItem> item;
  static void *getProc(void *, const char *name) {
    return reinterpret_cast<void *>(
        QOpenGLContext::currentContext()->getProcAddress(name));
  }
  static void wake(void *ctx) {
    auto self = static_cast<VideoRenderer *>(ctx);
    if (self->item)
      QMetaObject::invokeMethod(self->item, "update", Qt::QueuedConnection);
  }

public:
  ~VideoRenderer() {
    if (context) {
      mpv_render_context_set_update_callback(context, nullptr, nullptr);
      mpv_render_context_free(context);
    }
  }
  void synchronize(QQuickFramebufferObject *object) override {
    item = static_cast<VideoItem *>(object);
    if (context || !item->player())
      return;
    handle = item->player()->handle();
    mpv_opengl_init_params gl{getProc, nullptr};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE,
         const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl},
        {MPV_RENDER_PARAM_INVALID, nullptr}};
    int rc = mpv_render_context_create(&context, handle, params);
    if (rc < 0) {
      qWarning("Video renderer: %s", mpv_error_string(rc));
      return;
    }
    mpv_render_context_set_update_callback(context, wake, this);
  }
  void render() override {
    if (!context)
      return;
    auto f = framebufferObject();
    mpv_opengl_fbo fbo{int(f->handle()), f->width(), f->height(), 0};
    int flip = 0;
    mpv_render_param params[] = {{MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
                                 {MPV_RENDER_PARAM_FLIP_Y, &flip},
                                 {MPV_RENDER_PARAM_INVALID, nullptr}};
    mpv_render_context_update(context);
    mpv_render_context_render(context, params);
    QQuickOpenGLUtils::resetOpenGLState();
  }
  QOpenGLFramebufferObject *
  createFramebufferObject(const QSize &size) override {
    return new QOpenGLFramebufferObject(
        size, QOpenGLFramebufferObject::CombinedDepthStencil);
  }
};
QQuickFramebufferObject::Renderer *VideoItem::createRenderer() const {
  return new VideoRenderer;
}
