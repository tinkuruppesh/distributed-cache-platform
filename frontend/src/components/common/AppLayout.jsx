import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

export default function AppLayout() {
  const [open, setOpen] = useState(false);
  useLocation(); // re-render on navigation
  return (
    <div className="app">
      <Sidebar open={open} onNavigate={() => setOpen(false)} />
      <div className="main">
        <Topbar onMenu={() => setOpen((v) => !v)} />
        <main className="content"><Outlet /></main>
      </div>
    </div>
  );
}
