import {ApiOutlined, RobotOutlined} from '@ant-design/icons'
import Provider from './pages/Provider'
import Model from './pages/Model'

export const menuItems = [
    {key: '/providers', icon: <ApiOutlined/>, label: '供应商'},
    {key: '/models', icon: <RobotOutlined/>, label: '模型'},
]

const routeTable = [
    {path: 'providers', Component: Provider},
    {path: 'models', Component: Model},
]
export {routeTable}
export {default as Provider} from './pages/Provider'
export {default as Model} from './pages/Model'
