import { useRef, useMemo } from "react"
import { Canvas } from "@react-three/fiber"
import { OrbitControls, Stars, PerspectiveCamera, OrthographicCamera } from "@react-three/drei"

const BLOCK_COLORS = {
  // Природные поверхности
  "minecraft:grass_block": "#5d9948",
  "minecraft:dirt": "#866043",
  "minecraft:sand": "#d7cc8d",
  "minecraft:gravel": "#737373",
  "minecraft:snow": "#ffffff",
  "minecraft:ice": "#a5d1ff",
  "minecraft:clay": "#a1a7b1",
  
  // Жидкости
  "minecraft:water": "#1a4dc0",
  "minecraft:lava": "#d35c05",

  // Камни и породы
  "minecraft:stone": "#7d7d7d",
  "minecraft:cobblestone": "#666666",
  "minecraft:deepslate": "#515151",
  "minecraft:andesite": "#838383",
  "minecraft:diorite": "#bfbfbf",
  "minecraft:granite": "#9b6b60",
  "minecraft:tuff": "#6b6c65",
  "minecraft:bedrock": "#333333",
  "minecraft:obsidian": "#14101c",
  
  // Дерево (стволы и листва)
  "minecraft:oak_log": "#6a513b",
  "minecraft:oak_leaves": "#345e1a",
  "minecraft:spruce_log": "#3d2b1f",
  "minecraft:spruce_leaves": "#2d3e1d",
  "minecraft:birch_log": "#d6d6d6",
  "minecraft:birch_leaves": "#517431",
  "minecraft:jungle_log": "#56441b",
  "minecraft:jungle_leaves": "#438b13",
  "minecraft:acacia_log": "#635e5a",
  "minecraft:acacia_leaves": "#537b09",
  "minecraft:dark_oak_log": "#321f0e",
  "minecraft:dark_oak_leaves": "#254109",
  
  // Руды
  "minecraft:iron_ore": "#a1938b",
  "minecraft:deepslate_iron_ore": "#746962",
  "minecraft:coal_ore": "#2f2f2f",
  "minecraft:deepslate_coal_ore": "#1d1d1d",
  "minecraft:gold_ore": "#fcee4b",
  "minecraft:deepslate_gold_ore": "#8d812e",
  "minecraft:diamond_ore": "#5decf5",
  "minecraft:deepslate_diamond_ore": "#338e94",
  "minecraft:redstone_ore": "#941010",
  "minecraft:deepslate_redstone_ore": "#601212",
  "minecraft:emerald_ore": "#17dd62",
  "minecraft:deepslate_emerald_ore": "#137443",
  "minecraft:copper_ore": "#d06a4c",
  "minecraft:deepslate_copper_ore": "#774a3f",
}

function Chunk({ data, dictionary, mode, minY, maxY, avgY }) {
  if (!data || !data.columns || dictionary.length === 0) {
    console.log("[MapView] Chunk data or dictionary missing", { hasData: !!data, dictSize: dictionary.length });
    return null;
  }

  const cubes = []
  data.columns.forEach((column, i) => {
    const x = i % 16
    const z = Math.floor(i / 16)
    const topY = data.topY[i]

    column.forEach((blockIdInt, depth) => {
      const y = topY - depth
      const blockId = dictionary[blockIdInt] || "air";
      
      if (blockId === "air" || blockId === "minecraft:air") return
      if (y < minY || y > maxY) return

      const cleanId = blockId.includes(':') ? blockId : 'minecraft:' + blockId;
      const color = BLOCK_COLORS[blockId] || BLOCK_COLORS[cleanId] || "#777";
      
      if (mode === "2d" && depth > 0) return

      cubes.push(
        <mesh key={i + "-" + depth} position={[x - 8, y - avgY, z - 8]}>
          <boxGeometry args={[0.95, mode === "2d" ? 0.05 : 1, 0.95]} />
          <meshStandardMaterial color={color} />
        </mesh>
      )
    })
  })

  console.log("[MapView] Rendered cubes:", cubes.length);
  return <group>{cubes}</group>
}

export default function MapView({ chunkData, dictionary, inspectedBlock, mode, minY, maxY }) {
  const avgY = useMemo(() => {
    if (inspectedBlock) return inspectedBlock.y;
    if (!chunkData || !chunkData.topY || chunkData.topY.length === 0) return 64;
    return chunkData.topY.reduce((a, b) => a + b, 0) / chunkData.topY.length;
  }, [chunkData, inspectedBlock]);

  return (
    <div className="map-canvas-container" style={{ width: "100%", height: "100%", background: "#0a0a0a", position: "relative" }}>
      <Canvas key={mode}>
        <color attach="background" args={["#0a0a0a"]} />

        {mode === "2d" ? (
          <OrthographicCamera makeDefault position={[0, 150, 0]} zoom={25} up={[0, 0, -1]} far={1000} near={-1000} />
        ) : (
          <PerspectiveCamera makeDefault position={[20, 20, 20]} fov={45} />
        )}

        <ambientLight intensity={2.0} />
        <pointLight position={[10, 150, 10]} intensity={3.5} />

        {mode !== "2d" && <Stars radius={100} depth={50} count={5000} factor={4} saturation={0} fade speed={1} />}

        <Chunk data={chunkData} dictionary={dictionary} mode={mode} minY={minY} maxY={maxY} avgY={avgY} />
        
        {inspectedBlock && (
          <mesh position={[0, 0, 0]}>
            <boxGeometry args={[1.1, 1.1, 1.1]} />
            <meshStandardMaterial color={BLOCK_COLORS[inspectedBlock.id] || (inspectedBlock.id.includes(':') ? BLOCK_COLORS[inspectedBlock.id] : BLOCK_COLORS['minecraft:'+inspectedBlock.id]) || "#2196f3"} />
          </mesh>
        )}

        <OrbitControls
          makeDefault
          enableRotate={mode !== "2d"}
          screenSpacePanning={true}
          target={[0, 0, 0]}
        />
        <gridHelper args={[200, 20, "#333", "#222"]} position={[0, -0.5, 0]} />
      </Canvas>

      <div style={{ position: "absolute", bottom: "20px", left: "20px", color: "#666", fontSize: "0.8rem", pointerEvents: "none", background: "rgba(0,0,0,0.5)", padding: "5px", borderRadius: "5px" }}>
        {mode.toUpperCase()} VIEW | Y: {minY} to {maxY} 
        {inspectedBlock && ` | Inspecting: ${inspectedBlock.id}`}
        {chunkData && ` | Chunk: ${chunkData.x}, ${chunkData.z}`}
      </div>
    </div>
  )
}
