import api from './api';

export const monitoringService = {
  overview: () => api.get('/monitoring/overview').then((r) => r.data),
  traffic: (seconds = 60) => api.get('/monitoring/traffic', { params: { seconds } }).then((r) => r.data),
  cachePerformance: (seconds = 60) => api.get('/monitoring/cache-performance', { params: { seconds } }).then((r) => r.data),
  memory: (points = 150) => api.get('/monitoring/memory', { params: { points } }).then((r) => r.data),
  replication: () => api.get('/monitoring/replication').then((r) => r.data),
  events: (limit = 50) => api.get('/monitoring/events', { params: { limit } }).then((r) => r.data),
  cacheOperations: (limit = 50) => api.get('/monitoring/cache-operations', { params: { limit } }).then((r) => r.data),
};
