import { useState, useEffect, useRef } from "react"
import "./App.css"
import MapView from "./MapView"

function App() {
  console.log("!!! APP STARTING !!!");
  const [players, setPlayers] = useState([])       
  const [isOnline, setIsOnline] = useState(false) 
  const [isWorldLoaded, setIsWorldLoaded] = useState(false)
  const [chunkData, setChunkData] = useState(null)
  const [dictionary, setDictionary] = useState([])
  const [viewMode, setViewMode] = useState("2d")  
  const [minY, setMinY] = useState(parseInt(localStorage.getItem('rtm_min_y')) || -64)
  const [maxY, setMaxHeight] = useState(parseInt(localStorage.getItem('rtm_max_y')) || 320)
  const [apiKey, setApiKey] = useState(localStorage.getItem('rtm_api_key') || '')
  
  const [panels, setPanels] = useState({ settings: true, inspector: false })
  const [inspectPos, setInspectPos] = useState({ x: 0, y: 64, z: 0 })
  const [inspectedBlock, setInspectedBlock] = useState(null)
  const [loading, setLoading] = useState(false)

  const chunkCache = useRef(new Map())
  const lastUpdate = useRef(0)
  const apiBase = window.location.port === '5173' ? 'http://localhost:8080' : '';

  const togglePanel = (name) => setPanels(prev => ({ ...prev, [name]: !prev[name] }))

  const updateApiKey = (val) => {
    setApiKey(val);
    localStorage.setItem('rtm_api_key', val);
  }

  const updateMinY = (val) => {
    const v = parseInt(val);
    setMinY(v);
    localStorage.setItem('rtm_min_y', v);
  }

  const updateMaxY = (val) => {
    const v = parseInt(val);
    setMaxHeight(v);
    localStorage.setItem('rtm_max_y', v);
  }

  const fetchAPI = async (url, timeout = 5000) => {
    const headers = { 'Content-Type': 'application/json' };
    if (apiKey) headers['X-API-Key'] = apiKey;
    
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), timeout);
    
    try {
        const res = await fetch(apiBase + url, { headers, signal: controller.signal });
        clearTimeout(id);
        if (!res.ok) throw new Error("Status: " + res.status);
        return res.json();
    } catch (e) {
        clearTimeout(id);
        throw e;
    }
  }

  const updateData = async () => {
    try {
      const statusData = await fetchAPI("/api/status", 2000)
      setIsOnline(true)
      const worldIsReady = statusData.world_loaded;
      setIsWorldLoaded(worldIsReady)
      
      if (worldIsReady) {
        if (dictionary.length === 0) {
          console.log("[RTM] Fetching dictionary...");
          const dict = await fetchAPI("/api/map/dictionary", 3000);
          setDictionary(dict);
        }

        const pData = await fetchAPI("/api/players", 2000)
        setPlayers(pData)

        const now = Date.now();
        if (now - lastUpdate.current > 3000) {
            lastUpdate.current = now;
            const dim = pData.length > 0 ? pData[0].dimension : "minecraft:overworld";
            const cx = 0;
            const cz = 0;
            
            const cacheKey = `${dim}_${cx}_${cz}_${viewMode}`;
            if (!chunkCache.current.has(cacheKey)) {
              console.log("[RTM] Requesting chunk:", cx, cz);
              loadChunk(dim, cx, cz);
            } else {
              setChunkData(chunkCache.current.get(cacheKey));
            }
        }
      }
    } catch (e) {
      console.error("[RTM] Update error:", e.message);
      setIsOnline(false);
      setIsWorldLoaded(false);
    }
  }

  const loadChunk = async (dim, x, z) => {
    setLoading(true)
    try {
      const url = `/api/map/chunk?dim=${encodeURIComponent(dim)}&x=${x}&z=${z}&mode=${viewMode}`;
      const data = await fetchAPI(url, 15000);
      const cacheKey = `${dim}_${x}_${z}_${viewMode}`;
      chunkCache.current.set(cacheKey, data);
      setChunkData(data);
    } catch (e) {
      console.error("Chunk error:", e);
    } finally {
      setLoading(false);
    }
  }

  const inspectBlock = async () => {
    setLoading(true)
    try {
      const dim = players.length > 0 ? players[0].dimension : "minecraft:overworld";
      const url = `/api/map/block?dim=${encodeURIComponent(dim)}&x=${inspectPos.x}&y=${inspectPos.y}&z=${inspectPos.z}`;
      const data = await fetchAPI(url, 5000);
      setInspectedBlock(data);
    } catch (e) {
      alert("Error: " + e.message);
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    updateData();
    const i = setInterval(updateData, 2000)
    return () => clearInterval(i)
  }, [apiKey, viewMode, dictionary.length])

  return (
    <>
      <div className="sidebar-area">
        <aside className="sidebar">
          <div className="brand">
            <h1 style={{ fontWeight: "normal" }}>RealTimeMap</h1>
            <div style={{fontSize: "0.6rem", textAlign: "center", marginBottom: "10px", display: "flex", justifyContent: "center", gap: "10px"}}>
              <span style={{color: isOnline ? "#4caf50" : "#f44336"}}>● {isOnline ? "ONLINE" : "OFFLINE"}</span>
              <span style={{color: isWorldLoaded ? "#4caf50" : "#ff9800"}}>● {isWorldLoaded ? "WORLD" : "MENU"}</span>
              {loading && <span style={{color: "#2196f3"}}>● SYNC</span>}
            </div>
          </div>

          <div className="panel">
            <div className="panel-header" onClick={() => togglePanel('settings')} style={{ fontWeight: "normal" }}>
              <span>⚙️ Settings</span>
              <span>{panels.settings ? '▼' : '▶'}</span>
            </div>
            {panels.settings && (
              <div className="panel-content">
                <div className="menu-section">
                  <label style={{ fontWeight: "normal" }}>Security Key</label>
                  <input type="password" placeholder="API Key..." className="coord-input" value={apiKey} onChange={e => updateApiKey(e.target.value)} />
                </div>
                <div className="menu-section">
                  <label style={{ fontWeight: "normal" }}>Mode</label>
                  <div className="nav-list">
                    <button onClick={() => { chunkCache.current.clear(); setViewMode("2d"); }} className={viewMode === "2d" ? "nav-btn active" : "nav-btn"}>2D Flat</button>
                    <button onClick={() => { chunkCache.current.clear(); setViewMode("3d"); }} className={viewMode === "3d" ? "nav-btn active" : "nav-btn"}>3D Isometric</button>
                  </div>
                </div>
                <div className="menu-section">
                  <label style={{ fontWeight: "normal" }}>Height Filter (Y)</label>
                  <div className="range-controls">
                    <div className="range-item">
                      <span>Min: {minY}</span>
                      <input type="range" min="-64" max="320" value={minY} onChange={e => updateMinY(e.target.value)} />
                    </div>
                    <div className="range-item">
                      <span>Max: {maxY}</span>
                      <input type="range" min="-64" max="320" value={maxY} onChange={e => updateMaxY(e.target.value)} />
                    </div>
                  </div>
                </div>
                <button className="nav-btn" style={{textAlign: "center", background: "#222"}} onClick={() => {
                   chunkCache.current.clear();
                   if (players.length > 0) loadChunk(players[0].dimension, 0, 0);
                }}>🔄 Force Reload</button>
              </div>
            )}
          </div>

          <div className="panel">
            <div className="panel-header" onClick={() => togglePanel('inspector')} style={{ fontWeight: "normal" }}>
              <span>🔍 Block Inspector</span>
              <span>{panels.inspector ? '▼' : '▶'}</span>
            </div>
            {panels.inspector && (
              <div className="panel-content">
                <div className="menu-section">
                   <label style={{ fontWeight: "normal" }}>Coordinates</label>
                   <div className="coord-inputs">
                     <input type="number" className="coord-input" placeholder="X" value={inspectPos.x} onChange={e => setInspectPos({...inspectPos, x: parseInt(e.target.value)||0})} />
                     <input type="number" className="coord-input" placeholder="Y" value={inspectPos.y} onChange={e => setInspectPos({...inspectPos, y: parseInt(e.target.value)||0})} />
                     <input type="number" className="coord-input" placeholder="Z" value={inspectPos.z} onChange={e => setInspectPos({...inspectPos, z: parseInt(e.target.value)||0})} />
                   </div>
                </div>
                <button className="nav-btn active" style={{textAlign: "center"}} onClick={inspectBlock}>GET BLOCK</button>
                {inspectedBlock && (
                  <div className="player-card" style={{marginTop: "5px", borderColor: "#2196f3"}}>
                    <div className="p-name" style={{color: "#2196f3"}}>{inspectedBlock.id.split(':').pop()}</div>
                    <div className="p-coord">At {inspectedBlock.x} {inspectedBlock.y} {inspectedBlock.z}</div>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="menu-section" style={{marginTop: "10px"}}>
            <label style={{ fontWeight: "normal" }}>Players ({players.length})</label>
            <div className="player-list">
              {players.map(p => (
                <div key={p.uuid} className="player-card">
                   <div className="p-name">{p.name}</div>
                   <div className="p-coord">{Math.round(p.x)} {Math.round(p.y)} {Math.round(p.z)}</div>
                </div>
              ))}
            </div>
          </div>
        </aside>
      </div>

      <div className="map-viewport">
        <MapView chunkData={chunkData} dictionary={dictionary} inspectedBlock={inspectedBlock} mode={viewMode} minY={minY} maxY={maxY} />
      </div>
    </>
  )
}

export default App
