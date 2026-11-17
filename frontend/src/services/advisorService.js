import api from './api';

export const advisorService = {
  list: (status, category) => api.get('/advisor/recommendations', { params: { ...(status ? { status } : {}), ...(category ? { category } : {}) } }).then((r) => r.data),
  get: (id) => api.get(`/advisor/recommendations/${id}`).then((r) => r.data),
  acknowledge: (id) => api.post(`/advisor/recommendations/${id}/acknowledge`).then((r) => r.data),
  resolve: (id) => api.post(`/advisor/recommendations/${id}/resolve`).then((r) => r.data),
  dismiss: (id) => api.post(`/advisor/recommendations/${id}/dismiss`).then((r) => r.data),
  run: () => api.post('/advisor/run').then((r) => r.data),
};
