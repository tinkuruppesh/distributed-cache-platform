import { nodeColor } from '../../utils/format';

const SIZE = 500;
const CENTER = SIZE / 2;
const RING_RADIUS = 188;
const KEY_RADIUS = 148;
const angle = (position) => position * 2 * Math.PI - Math.PI / 2;
const point = (position, radius) => [CENTER + radius * Math.cos(angle(position)), CENTER + radius * Math.sin(angle(position))];

/** Draws ownership from the backend ring positions. No physical-node positions are inferred. */
export default function HashRingVisualization({ ring, selectedNode, onSelectNode, selectedKey, onSelectKey, mappedKey }) {
  const nodeIds = Object.keys(ring.ownership || {});
  const virtualNodes = ring.virtualNodes || [];
  const sampledKeys = ring.keys || [];
  const spans = virtualNodes.map((virtualNode, index) => {
    const previous = virtualNodes[(index - 1 + virtualNodes.length) % virtualNodes.length];
    const start = previous.position;
    let distance = virtualNode.position - start;
    if (distance < 0) distance += 1;
    if (virtualNodes.length === 1) distance = 0.9999;
    const [x1, y1] = point(start, RING_RADIUS);
    const [x2, y2] = point(start + distance, RING_RADIUS);
    return {
      id: `${virtualNode.nodeId}-${virtualNode.index}-${index}`,
      node: virtualNode.nodeId,
      path: `M ${x1} ${y1} A ${RING_RADIUS} ${RING_RADIUS} 0 ${distance > 0.5 ? 1 : 0} 1 ${x2} ${y2}`,
    };
  });
  const selectedSample = sampledKeys.find((entry) => entry.key === selectedKey);
  const highlightedKey = mappedKey || selectedSample;

  return (
    <div>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} style={{ display: 'block', width: '100%', maxWidth: 540, margin: '0 auto' }} role="img" aria-label="Consistent hash ring. Colored arcs show primary ownership ranges; small marks show virtual nodes.">
        <defs>
          <marker id="ring-clockwise-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#74819a" />
          </marker>
        </defs>
        {virtualNodes.length > 0 ? <>
          <circle cx={CENTER} cy={CENTER} r={RING_RADIUS} fill="none" stroke="#e8ecf4" strokeWidth="20" />
          {spans.map((span) => (
            <path key={span.id} d={span.path} fill="none" stroke={nodeColor(span.node, nodeIds)} strokeWidth="15" opacity={selectedNode && selectedNode !== span.node ? 0.16 : 0.92} strokeLinecap="butt" style={{ cursor: 'pointer', transition: 'opacity 140ms ease' }} onClick={() => onSelectNode(span.node === selectedNode ? null : span.node)}>
              <title>{`${span.node} primary ownership range`}</title>
            </path>
          ))}
          {virtualNodes.map((virtualNode) => {
            const [x, y] = point(virtualNode.position, RING_RADIUS);
            return <circle key={`${virtualNode.nodeId}-${virtualNode.index}`} cx={x} cy={y} r="1.6" fill="#fff" opacity="0.9" aria-hidden="true" />;
          })}
          {sampledKeys.map((entry) => {
            const [x, y] = point(entry.position, KEY_RADIUS);
            const isSelected = entry.key === selectedKey;
            const isDimmed = selectedNode && entry.primary !== selectedNode && !entry.replicas.includes(selectedNode);
            return (
              <circle key={entry.key} cx={x} cy={y} r={isSelected ? 6 : 3.4} fill={nodeColor(entry.primary, nodeIds)} stroke={isSelected ? '#14213d' : '#fff'} strokeWidth={isSelected ? 2 : 1.2} opacity={isDimmed ? 0.18 : 0.94} style={{ cursor: 'pointer' }} onClick={() => onSelectKey(isSelected ? null : entry.key)}>
                <title>{`${entry.key}\nPrimary: ${entry.primary}\nReplicas: ${entry.replicas.join(', ') || 'none'}`}</title>
              </circle>
            );
          })}
          {highlightedKey && (() => {
            const [x, y] = point(highlightedKey.position, KEY_RADIUS);
            return <g aria-label={`Selected key ${highlightedKey.key}`}>
              <circle cx={x} cy={y} r="10" fill="none" stroke="#172b4d" strokeWidth="2" />
              <circle cx={x} cy={y} r="5" fill={nodeColor(highlightedKey.primary, nodeIds)} stroke="#fff" strokeWidth="1.5" />
            </g>;
          })()}
          <path d={`M ${CENTER - 32} ${CENTER - RING_RADIUS - 14} A ${RING_RADIUS + 14} ${RING_RADIUS + 14} 0 0 1 ${CENTER + 34} ${CENTER - RING_RADIUS - 14}`} fill="none" stroke="#74819a" strokeWidth="1.5" markerEnd="url(#ring-clockwise-arrow)" />
          <text x={CENTER} y={CENTER - 12} textAnchor="middle" style={{ fontSize: 14, fontWeight: 700, fill: '#14213d' }}>Hash space</text>
          <text x={CENTER} y={CENTER + 9} textAnchor="middle" style={{ fontSize: 12, fill: '#667391' }}>{nodeIds.length} physical nodes</text>
          <text x={CENTER} y={CENTER + 29} textAnchor="middle" style={{ fontSize: 12, fill: '#667391' }}>{virtualNodes.length} virtual points</text>
          <text x={CENTER} y={CENTER + 49} textAnchor="middle" style={{ fontSize: 11, fill: '#667391' }}>Clockwise lookup →</text>
        </> : <>
          <circle cx={CENTER} cy={CENTER} r={RING_RADIUS} fill="#f7f9fc" stroke="#dfe5ef" strokeWidth="2" />
          <text x={CENTER} y={CENTER - 4} textAnchor="middle" style={{ fontSize: 14, fontWeight: 650, fill: '#667391' }}>Ring positions unavailable</text>
          <text x={CENTER} y={CENTER + 18} textAnchor="middle" style={{ fontSize: 12, fill: '#8893a8' }}>No virtual nodes were returned by the cluster.</text>
        </>}
      </svg>
      <div style={{ display: 'flex', justifyContent: 'center', gap: 14, flexWrap: 'wrap', marginTop: 4 }}>
        <span className="muted small"><span className="node-dot" style={{ background: '#667391', marginRight: 6 }} />Physical node colors identify each owner</span>
        <span className="muted small"><span style={{ display: 'inline-block', width: 9, height: 9, borderRadius: '50%', background: '#667391', margin: '0 6px 0 2px' }} />Dots inside are sampled real cache keys</span>
      </div>
    </div>
  );
}
