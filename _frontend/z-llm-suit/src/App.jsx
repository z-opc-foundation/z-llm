import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-llm-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/providers" replace/>}/>
            <Route path="/" element={
                <AppLayout
                    menuItems={menuItems}
                    appTitle="z-llm 大模型中心"
                    appShort="LLM"
                    appIcon={{icon: <img src="/icon.png" alt="z-llm" style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8}}/>, color: '#0d9488', label: 'z-llm'}}
                />
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
