#pragma once
#include <QAbstractListModel>
#include <QVariantList>
#include <QSet>
#include <QHash>

// preserve delegates, selection and scroll positions when only row data changes
class StableModel : public QAbstractListModel {
  Q_OBJECT
  Q_PROPERTY(QVariantList rows READ rows WRITE setRows NOTIFY rowsChanged)
public:
  explicit StableModel(QObject *parent = nullptr)
      : QAbstractListModel(parent) {}
  QVariantList rows() const { return values; }
  int rowCount(const QModelIndex &parent = {}) const override {
    return parent.isValid() ? 0 : values.size();
  }
  QHash<int, QByteArray> roleNames() const override {
    return {{Qt::UserRole + 1, "record"}};
  }
  QVariant data(const QModelIndex &index, int role) const override {
    return index.isValid() && index.row() < values.size() &&
                   role == Qt::UserRole + 1
               ? values[index.row()]
               : QVariant{};
  }
  Q_INVOKABLE void patchTracks(const QVariantMap &patches) {
    if(patches.isEmpty())return;
    for(auto p=patches.cbegin();p!=patches.cend();++p){
      const auto fields=p.value().toMap();
      for(int i:trackRows.value(p.key())){
        auto row=values[i].toMap();bool changed=false;
        for(auto f=fields.cbegin();f!=fields.cend();++f)if(row.value(f.key())!=f.value()){row[f.key()]=f.value();changed=true;}
        if(changed){values[i]=row;emit dataChanged(index(i),index(i),{Qt::UserRole+1});}
      }
    }
  }
  void setRows(const QVariantList &desired) {
    if (values == desired) return;
    QHash<QString, int> desiredPositions;
    bool unique = true;
    for (int i = 0; i < desired.size(); ++i) {
      const auto key = identity(desired[i]);
      if (desiredPositions.contains(key)) { unique = false; break; }
      desiredPositions.insert(key, i);
    }
    QSet<QString> existingKeys;
    for (const auto &value : values) {
      const auto key = identity(value);
      if (existingKeys.contains(key)) unique = false;
      existingKeys.insert(key);
    }
    if (!unique) {
      // keep the legacy occurrence behavior for callers without stable unique keys
    for (int i = 0; i < desired.size(); ++i) {
      QString key = identity(desired[i]);
      int found = -1;
      for (int j = i; j < values.size(); ++j)
        if (identity(values[j]) == key) {
          found = j;
          break;
        }
      if (found < 0) {
        beginInsertRows({}, i, i);
        values.insert(i, desired[i]);
        endInsertRows();
      } else {
        if (found != i) {
          beginMoveRows({}, found, found, {}, i);
          values.move(found, i);
          endMoveRows();
        }
        if (values[i] != desired[i]) {
          values[i] = desired[i];
          emit dataChanged(index(i), index(i), {Qt::UserRole + 1});
        }
      }
    }
    if (values.size() > desired.size()) {
      beginRemoveRows({}, desired.size(), values.size() - 1);
      values.erase(values.begin() + desired.size(), values.end());
      endRemoveRows();
    }
      rebuildTrackIndex();
      emit rowsChanged();
      return;
    }
    // remove contiguous runs, then append new rows in one notification
    // Existing persistent indexes stay attached to their song/playlist occurrence
    for (int end = values.size() - 1; end >= 0;) {
      if (desiredPositions.contains(identity(values[end]))) { --end; continue; }
      int first = end;
      while (first > 0 && !desiredPositions.contains(identity(values[first - 1]))) --first;
      beginRemoveRows({}, first, end);
      values.erase(values.begin() + first, values.begin() + end + 1);
      endRemoveRows();
      end = first - 1;
    }
    QVariantList additions;
    for (const auto &value : desired)
      if (!existingKeys.contains(identity(value))) additions << value;
    if (!additions.isEmpty()) {
      const int first = values.size();
      beginInsertRows({}, first, first + additions.size() - 1);
      values += additions;
      endInsertRows();
    }
    bool reordered = false;
    for (int i = 0; i < values.size(); ++i)
      if (identity(values[i]) != identity(desired[i])) { reordered = true; break; }
    if (reordered) {
      emit layoutAboutToBeChanged();
      const auto oldIndexes = persistentIndexList();
      QModelIndexList newIndexes;
      newIndexes.reserve(oldIndexes.size());
      for (const auto &old : oldIndexes)
        newIndexes << index(desiredPositions.value(identity(values[old.row()])), old.column());
      values = desired;
      changePersistentIndexList(oldIndexes, newIndexes);
      emit layoutChanged();
    } else {
      // notify only records whose fields changed
      // a whole-range notification reevaluates every visible delegate for a single like or speed setting
      int first=-1;
      for(int i=0;i<values.size();++i){
        const bool changed=values[i]!=desired[i];
        if(changed){values[i]=desired[i];if(first<0)first=i;}
        else if(first>=0){emit dataChanged(index(first),index(i-1),{Qt::UserRole+1});first=-1;}
      }
      if(first>=0)emit dataChanged(index(first),index(values.size()-1),{Qt::UserRole+1});
    }
    if(reordered && !values.isEmpty())emit dataChanged(index(0),index(values.size()-1),{Qt::UserRole+1});
    rebuildTrackIndex();
    emit rowsChanged();
  }
signals:
  void rowsChanged();

private:
  QVariantList values;
  QHash<QString,QList<int>> trackRows;
  void rebuildTrackIndex() {
    trackRows.clear();
    for(int i=0;i<values.size();++i)trackRows[values[i].toMap().value("id").toString()].append(i);
  }
  static QString identity(const QVariant &value) {
    auto row = value.toMap();
    return row.value("entryId", row.value("memberId", row.value("key", row.value("id"))))
        .toString();
  }
};
