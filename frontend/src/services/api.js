import axios from 'axios';

const TOKEN_KEY = 'dcp.token';

// Tokens live in sessionStorage: they vanish when the tab closes (safer than localStorage for admin tools).
export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (t) => sessionStorage.setItem(TOKEN_KEY, t),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

const api = axios.create({ baseURL: '/api/v1', timeout: 20000 });

api.interceptors.request.use((cfg) => {
  const t = tokenStore.get();
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401 && !String(err.config?.url).includes('/auth/login')) {
      tokenStore.clear();
      window.dispatchEvent(new Event('dcp:unauthorized'));
    }
    return Promise.reject(err);
  },
);

export const errorMessage = (e) => e?.response?.data?.message || e?.message || 'Request failed';
export default api;
