#!groovy

def decidePipeline(Map configMap){
    def type = configMap.get("type")

    switch(type){
        case "javaEKS":
                javaEKS(configMap)
        break

        case "nodeJSEKS":
                javaVM(configMap)
        break

        default:
            error "type is not matched"
        break
    }
}