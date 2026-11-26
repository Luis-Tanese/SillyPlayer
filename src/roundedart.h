#pragma once
#include <QFileInfo>
#include <QImage>
#include <QImageReader>
#include <QPainter>
#include <QPainterPath>
#include <QQuickImageProvider>
#include <QUrl>
#include <algorithm>
#include <cmath>

// async DPR-aware rounded-corner artwork loader so rectangular covers fill squares crisply without upscaling blur
class RoundedArtProvider : public QQuickImageProvider {
public:
  RoundedArtProvider()
      : QQuickImageProvider(QQuickImageProvider::Image,
                            QQmlImageProviderBase::ForceAsynchronousImageLoading) {}

  QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override {
    const int s1 = id.indexOf('/');
    const int s2 = s1 > 0 ? id.indexOf('/', s1 + 1) : -1;
    if (s1 <= 0 || s2 <= s1 + 1) return {};
    const bool crop = id.left(s1) != QLatin1String("0");
    const double radiusFrac = std::clamp(id.mid(s1 + 1, s2 - s1 - 1).toDouble(), 0.0, 0.5);
    const QString raw = QUrl::fromPercentEncoding(id.mid(s2 + 1).toUtf8());
    const QUrl url(raw);
    const QString path = url.isLocalFile() ? url.toLocalFile() : raw;
    if (path.isEmpty() || !QFileInfo::exists(path)) return {};

    QImageReader reader(path);
    reader.setAutoTransform(true);
    const QSize orig = reader.size();
    const int target = std::clamp(std::max(requestedSize.width(), requestedSize.height()), 32, 1024);

    if (orig.isValid() && orig.width() > 0 && orig.height() > 0) {
      if (crop) {
        // scale only when the short side is comfortably larger than target so 16:9 covers never upscale
        const int shortSide = std::min(orig.width(), orig.height());
        if (shortSide > target * 2) {
          const double factor = double(target * 2) / double(shortSide);
          reader.setScaledSize(QSize(std::max(1, int(std::lround(orig.width() * factor))),
                                     std::max(1, int(std::lround(orig.height() * factor)))));
        }
      } else {
        const int longSide = std::max(orig.width(), orig.height());
        if (longSide > target * 2) {
          const double factor = double(target * 2) / double(longSide);
          reader.setScaledSize(QSize(std::max(1, int(std::lround(orig.width() * factor))),
                                     std::max(1, int(std::lround(orig.height() * factor)))));
        }
      }
    }

    QImage img = reader.read();
    if (img.isNull()) return {};

    if (!crop) {
      if (img.width() > target || img.height() > target) {
        img = img.scaled(target, target, Qt::KeepAspectRatio, Qt::SmoothTransformation);
      }
      if (size) *size = img.size();
      return img;
    }

    // center-crop to square at native resolution (never upscale small covers), then downscale smoothly
    const int side = std::min(img.width(), img.height());
    if (img.width() != img.height()) {
      img = img.copy((img.width() - side) / 2, (img.height() - side) / 2, side, side);
    }
    const int outSide = std::min(side, target);
    if (img.width() != outSide) {
      img = img.scaled(outSide, outSide, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    }

    QImage rounded(outSide, outSide, QImage::Format_ARGB32_Premultiplied);
    rounded.fill(Qt::transparent);
    QPainter p(&rounded);
    p.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform, true);
    const double r = std::min(outSide * 0.5, outSide * radiusFrac);
    QPainterPath clipPath;
    clipPath.addRoundedRect(QRectF(0, 0, outSide, outSide), r, r);
    p.setClipPath(clipPath);
    p.drawImage(0, 0, img);
    p.end();

    if (size) *size = rounded.size();
    return rounded;
  }
};
