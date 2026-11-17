import api from './api';

const enc = encodeURIComponent;

export const cacheService = {
  set: (key, value, ttlSeconds) => api.post('/cache', { key, value, ...(ttlSeconds !== undefined && ttlSeconds !== '' ? { ttlSeconds: Number(ttlSeconds) } : {}) }).then((r) => r.data),
  update: (key, value, ttlSeconds) => api.put(`/cache/${enc(key)}`, { value, ttlSeconds: Number(ttlSeconds) }).then((r) => r.data),
  get: (key) => api.get(`/cache/${enc(key)}`).then((r) => r.data),
  remove: (key) => api.delete(`/cache/${enc(key)}`).then((r) => r.data),
  exists: (key) => api.get(`/cache/${enc(key)}/exists`).then((r) => r.data),
  ttl: (key) => api.get(`/cache/${enc(key)}/ttl`).then((r) => r.data),
  expire: (key, ttlSeconds) => api.post(`/cache/${enc(key)}/expire`, { ttlSeconds: Number(ttlSeconds) }).then((r) => r.data),
  locate: (key) => api.get(`/cache/${enc(key)}/locate`).then((r) => r.data),
  keys: (prefix = '', limit = 100, reveal = false, filters = {}) => api.get('/cache/keys', { params: { prefix, limit, reveal, ...filters } }).then((r) => r.data),
  read: (key) => api.get(`/cache/${enc(key)}/read`).then((r) => r.data),
  bulkDelete: (keys) => api.post('/cache/bulk-delete', { keys }).then((r) => r.data),
  clear: () => api.delete('/cache/clear').then((r) => r.data),
};
