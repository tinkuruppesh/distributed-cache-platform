import api from './api.js';

export const simulationService = {
  start: (body) => api.post('/simulations', body).then((r) => r.data),
  list: (q = '') => api.get('/simulations', { params: q ? { q } : {} }).then((r) => r.data),
  analytics: (params = {}) => api.get('/simulations/analytics', { params }).then((r) => r.data),
  get: (id) => api.get(`/simulations/${id}`).then((r) => r.data),
  restartNode: (id) => api.post(`/simulations/${id}/restart-node`).then((r) => r.data),
  stop: (id) => api.post(`/simulations/${id}/stop`).then((r) => r.data),
  report: (id) => api.get(`/simulations/${id}/report`).then((r) => r.data),
};
