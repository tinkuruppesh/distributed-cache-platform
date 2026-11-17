import { X } from 'lucide-react';

export default function Modal({ open, title, onClose, wide, children }) {
  if (!open) return null;
  return (
    <div className="overlay" onClick={onClose}>
      <div className={`dialog ${wide ? 'wide' : ''}`} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="spread" style={{ marginBottom: 10 }}>
          <h2>{title}</h2>
          <button className="btn ghost small" onClick={onClose} aria-label="Close"><X size={16} /></button>
        </div>
        {children}
      </div>
    </div>
  );
}
