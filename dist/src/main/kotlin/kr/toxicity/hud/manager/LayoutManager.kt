package kr.toxicity.hud.manager

import kr.toxicity.hud.api.plugin.ReloadInfo
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.layout.LayoutGroup
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.util.*
import java.io.File

object LayoutManager : BetterHudManager {

    override val managerName: String = "Layout"
    override val supportExternalPacks: Boolean = true

    private val layoutMap = HashMap<String, LayoutGroup>()
    private val templateMap = HashMap<String, YamlObject>()

    override fun start() {

    }

    fun getLayout(name: String) = synchronized(layoutMap) {
        layoutMap[name]
    }

    fun getTemplate(name: String) = synchronized(templateMap) {
        templateMap[name]
    }

    override fun preReload() {
        layoutMap.clear()
        templateMap.clear()
    }

    override fun reload(workingDirectory: File, info: ReloadInfo, resource: GlobalResource) {
        workingDirectory.subFolder("layouts").forEachAllYaml(info.sender) { file, s, yamlObject ->
            if (yamlObject.getAsBoolean("template", false)) {
                synchronized(templateMap) {
                    templateMap[s] = yamlObject
                }
            } else runCatching {
                layoutMap.putSync("layout") {
                    LayoutGroup(s, info.sender, yamlObject)
                }
            }.handleFailure(info) {
                "Unable to load this layout: $s in ${file.name}"
            }
        }
    }

    override fun end() {
    }
}
