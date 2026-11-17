import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Orbit } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { errorMessage } from '../services/api';

export default function Login() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to={location.state?.from || '/'} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await login(username.trim(), password); navigate(location.state?.from || '/', { replace: true }); }
    catch (err) { setError(errorMessage(err)); }
    setBusy(false);
  };

  return (
    <div className="login-wrap">
      <form className="card login-card stack" onSubmit={submit}>
        <div className="row"><div className="brand" style={{ border: 'none', padding: 0, height: 'auto' }}><div className="logo"><Orbit size={18} /></div><div>Cache Platform<small>Self-healing distributed cache</small></div></div></div>
        <div><h1>Sign in</h1><p className="muted">Use your administrator, operator or viewer account.</p></div>
        {error && <div className="alert error">{error}</div>}
        <label className="field">Username<input autoFocus value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required /></label>
        <label className="field">Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        <button className="btn primary" disabled={busy} type="submit" style={{ justifyContent: 'center' }}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  );
}
