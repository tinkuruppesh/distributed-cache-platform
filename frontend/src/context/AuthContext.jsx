import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import api, { tokenStore } from '../services/api';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);

  // restore the session if a token is still present
  useEffect(() => {
    let alive = true;
    (async () => {
      if (!tokenStore.get()) { setReady(true); return; }
      try {
        const { data } = await api.get('/auth/me');
        if (alive) setUser({ username: data.username, role: data.roles[0] });
      } catch { tokenStore.clear(); }
      if (alive) setReady(true);
    })();
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const onUnauthorized = () => setUser(null);
    window.addEventListener('dcp:unauthorized', onUnauthorized);
    return () => window.removeEventListener('dcp:unauthorized', onUnauthorized);
  }, []);

  const login = useCallback(async (username, password) => {
    const { data } = await api.post('/auth/login', { username, password });
    tokenStore.set(data.token);
    setUser({ username: data.username, role: data.role });
  }, []);

  const logout = useCallback(async () => {
    try { await api.post('/auth/logout'); } catch { /* ignore */ }
    tokenStore.clear();
    setUser(null);
  }, []);

  const hasRole = useCallback((...roles) => !!user && roles.includes(user.role), [user]);
  const value = useMemo(() => ({ user, ready, login, logout, hasRole }), [user, ready, login, logout, hasRole]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
