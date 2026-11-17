import api from './api';

export const clusterService = {
  cluster: () => api.get('/cluster').then((r) => r.data),
  nodes: () => api.get('/cluster/nodes').then((r) => r.data),
  node: (id) => api.get(`/cluster/nodes/${id}`).then((r) => r.data),
  addNode: (body) => api.post('/cluster/nodes', body).then((r) => r.data),
  removeNode: (id) => api.delete(`/cluster/nodes/${id}`).then((r) => r.data),
  maintenance: (id) => api.post(`/cluster/nodes/${id}/maintenance`).then((r) => r.data),
  resume: (id) => api.post(`/cluster/nodes/${id}/resume`).then((r) => r.data),
  hashRing: (sample = 60) => api.get('/cluster/hash-ring', { params: { sample } }).then((r) => r.data),
  health: () => api.get('/cluster/health').then((r) => r.data),
  events: (limit = 50) => api.get('/cluster/events', { params: { limit } }).then((r) => r.data),
  gossip: () => api.get('/cluster/gossip').then((r) => r.data),
  failovers: () => api.get('/cluster/failovers').then((r) => r.data),
  settings: () => api.get('/settings').then((r) => r.data),
  saveSettings: (s) => api.put('/settings', s).then((r) => r.data),
  auditLogs: (q = '', page = 0, size = 25, filters = {}) => api.get('/audit-logs', {
    params: { q: q || undefined, page, size, ...Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== '' && value != null)) },
  }).then((r) => r.data),
};
